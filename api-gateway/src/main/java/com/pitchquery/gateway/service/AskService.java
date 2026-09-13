package com.pitchquery.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pitchquery.gateway.config.GatewayProperties;
import com.pitchquery.gateway.dto.AskResponse;
import com.pitchquery.gateway.dto.openai.ChatMessage;
import com.pitchquery.gateway.dto.openai.ToolCall;
import com.pitchquery.gateway.dto.openai.ToolDefinition;
import com.pitchquery.gateway.dto.scraper.ScraperSnapshot;
import com.pitchquery.gateway.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Orchestrates the OpenAI tool-calling loop described in PLAN.md: the model
 * may call {@code get_latest_espn_data} as needed, then must emit exactly
 * one final JSON object matching the {@code /api/ask} response shape.
 */
@Service
public class AskService {

    private static final Logger log = LoggerFactory.getLogger(AskService.class);

    private static final Set<String> ALLOWED_ANSWER_TYPES = Set.of(
            AskResponse.TYPE_STANDINGS, AskResponse.TYPE_FIXTURES, AskResponse.TYPE_RESULTS, AskResponse.TYPE_TEXT);

    private static final String TOOL_NAME = "get_latest_espn_data";

    private static final String TOOL_PARAMETERS_JSON = """
            {
              "type": "object",
              "properties": {
                "league": {
                  "type": "string",
                  "enum": ["premier-league"],
                  "description": "League slug"
                },
                "data_type": {
                  "type": "string",
                  "enum": ["standings", "fixtures", "results"],
                  "description": "Which kind of data to fetch"
                }
              },
              "required": ["league", "data_type"],
              "additionalProperties": false
            }
            """;

    private static final String SYSTEM_PROMPT = """
            You are Pitch Query, a soccer Q&A assistant backed by live ESPN data.

            You have one tool available: get_latest_espn_data(league, data_type). Call it as many
            times as needed (e.g. once for standings, once for fixtures) to answer the user's
            question with fresh data. Only "premier-league" is supported for league, and data_type
            must be one of "standings", "fixtures", "results". For questions that don't map to any
            of those (e.g. "who is Arsenal's manager?"), don't call the tool -- just answer directly.

            Once you have everything you need, respond with EXACTLY ONE JSON object and nothing
            else -- no prose before or after it, no markdown code fences. The object must have
            exactly these fields:
              "answer_type": one of "standings", "fixtures", "results", "text"
              "league": the league slug this answer is about (e.g. "premier-league"), or null if not applicable
              "summary": a short natural-language answer to the user's question
              "data": the structured data backing the answer (the same shape returned by the tool,
                      i.e. {"table": [...]} for standings, {"fixtures": [...]} for fixtures,
                      {"results": [...]} for results), or null when answer_type is "text"
              "generated_at": any value -- it will be overwritten by the server

            Never invent standings, fixtures, or results data yourself -- always fetch it via the tool.
            """;

    private final OpenAiClient openAiClient;
    private final ScraperServiceClient scraperServiceClient;
    private final ObjectMapper objectMapper;
    private final int maxIterations;
    private final List<ToolDefinition> tools;

    public AskService(OpenAiClient openAiClient,
                       ScraperServiceClient scraperServiceClient,
                       ObjectMapper objectMapper,
                       GatewayProperties properties) {
        this.openAiClient = openAiClient;
        this.scraperServiceClient = scraperServiceClient;
        this.objectMapper = objectMapper;
        this.maxIterations = Math.max(1, properties.getToolLoop().getMaxIterations());
        this.tools = List.of(ToolDefinition.function(buildFunctionDefinition()));
    }

    private ToolDefinition.FunctionDefinition buildFunctionDefinition() {
        try {
            JsonNode parameters = objectMapper.readTree(TOOL_PARAMETERS_JSON);
            return new ToolDefinition.FunctionDefinition(
                    TOOL_NAME,
                    "Fetch the latest data for a league from ESPN (standings table, upcoming fixtures, "
                            + "or recent results). Always returns fresh-enough data -- refreshes automatically "
                            + "if the cache is stale or missing.",
                    parameters);
        } catch (IOException ex) {
            throw new IllegalStateException("Invalid hardcoded tool schema", ex);
        }
    }

    public AskResponse ask(String question) {
        if (question == null || question.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "question must not be blank");
        }

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(SYSTEM_PROMPT));
        messages.add(ChatMessage.user(question));

        String finalContent = runToolLoop(messages);

        AskResponse parsed = tryParseAndValidate(finalContent);
        if (parsed != null) {
            return withGeneratedAt(parsed);
        }

        log.warn("LLM final content failed to parse/validate on first attempt, retrying once");
        AskResponse retried = retryForValidJson(messages, finalContent);
        if (retried != null) {
            return withGeneratedAt(retried);
        }

        log.warn("LLM final content still not valid JSON after retry; falling back to a text answer");
        return new AskResponse(
                AskResponse.TYPE_TEXT,
                null,
                fallbackSummary(finalContent),
                null,
                nowUtc());
    }

    /** Drives the tool-calling loop until the model returns a message with no tool calls. */
    private String runToolLoop(List<ChatMessage> messages) {
        for (int i = 0; i < maxIterations; i++) {
            ChatMessage reply = openAiClient.chatCompletion(messages, tools);

            if (reply.toolCalls() != null && !reply.toolCalls().isEmpty()) {
                messages.add(ChatMessage.assistant(reply.content(), reply.toolCalls()));
                for (ToolCall call : reply.toolCalls()) {
                    String toolResult = executeTool(call);
                    messages.add(ChatMessage.toolResult(call.id(), toolResult));
                }
                continue;
            }

            if (reply.content() == null || reply.content().isBlank()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "OpenAI returned an empty final answer");
            }
            return reply.content();
        }
        throw new ApiException(HttpStatus.BAD_GATEWAY,
                "LLM did not produce a final answer within " + maxIterations + " tool-calling turns");
    }

    /** Executes one LLM-requested tool call against scraper-service and returns the stringified result. */
    private String executeTool(ToolCall call) {
        if (call.function() == null || !TOOL_NAME.equals(call.function().name())) {
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "LLM requested an unknown tool: " + (call.function() != null ? call.function().name() : "null"));
        }

        JsonNode args;
        try {
            args = objectMapper.readTree(call.function().arguments());
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "LLM produced invalid tool call arguments", ex);
        }

        String league = args.path("league").asText(null);
        String dataType = args.path("data_type").asText(null);
        if (league == null || league.isBlank() || dataType == null || dataType.isBlank()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "LLM tool call is missing league/data_type");
        }

        ScraperSnapshot snapshot = scraperServiceClient.fetchFreshData(league, dataType);

        ObjectNode result = objectMapper.createObjectNode();
        result.set("data", snapshot.data());
        result.put("scraped_at", snapshot.scraped_at());
        result.put("season", snapshot.season());
        return result.toString();
    }

    /** One retry: ask the model to strictly reformat as JSON only, per PLAN.md's "handle parse failures gracefully". */
    private AskResponse retryForValidJson(List<ChatMessage> messages, String previousContent) {
        messages.add(ChatMessage.assistant(previousContent, null));
        messages.add(ChatMessage.user(
                "Your previous reply was not a single valid JSON object matching the required shape. "
                        + "Reply again with ONLY one JSON object with fields answer_type, league, summary, data, "
                        + "generated_at -- no prose, no markdown fences."));
        try {
            ChatMessage retryReply = openAiClient.chatCompletion(messages, tools);
            return tryParseAndValidate(retryReply.content());
        } catch (ApiException ex) {
            log.warn("Retry call to OpenAI failed: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * Parses and validates the LLM's final content against the /api/ask contract:
     * required fields present, answer_type in the allowed enum, data null only when
     * answer_type == "text". Returns null (never throws) on any validation failure.
     */
    private AskResponse tryParseAndValidate(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(stripMarkdownFence(content));
        } catch (IOException ex) {
            log.warn("Failed to parse LLM final JSON: {}", ex.getMessage());
            return null;
        }

        if (!root.isObject()) {
            return null;
        }
        if (!root.has("answer_type") || !root.has("league") || !root.has("summary") || !root.has("data")) {
            return null;
        }

        JsonNode answerTypeNode = root.get("answer_type");
        if (!answerTypeNode.isTextual() || !ALLOWED_ANSWER_TYPES.contains(answerTypeNode.asText())) {
            return null;
        }
        String answerType = answerTypeNode.asText();

        JsonNode summaryNode = root.get("summary");
        if (!summaryNode.isTextual() || summaryNode.asText().isBlank()) {
            return null;
        }
        String summary = summaryNode.asText();

        JsonNode leagueNode = root.get("league");
        String league;
        if (leagueNode.isNull()) {
            league = null;
        } else if (leagueNode.isTextual()) {
            league = leagueNode.asText();
        } else {
            return null;
        }

        JsonNode dataNode = root.get("data");
        boolean dataIsNull = dataNode == null || dataNode.isNull();
        if (AskResponse.TYPE_TEXT.equals(answerType)) {
            if (!dataIsNull) {
                return null;
            }
        } else if (dataIsNull || !dataNode.isObject()) {
            return null;
        }

        return new AskResponse(answerType, league, summary, dataIsNull ? null : dataNode, null);
    }

    private String stripMarkdownFence(String content) {
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline != -1) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3);
            }
        }
        return trimmed.trim();
    }

    private AskResponse withGeneratedAt(AskResponse parsed) {
        return new AskResponse(parsed.answer_type(), parsed.league(), parsed.summary(), parsed.data(), nowUtc());
    }

    private String fallbackSummary(String raw) {
        if (raw == null || raw.isBlank()) {
            return "The assistant could not produce a valid answer for this question.";
        }
        String cleaned = raw.trim();
        int limit = 2000;
        return cleaned.length() > limit ? cleaned.substring(0, limit) + "..." : cleaned;
    }

    private String nowUtc() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
    }
}
