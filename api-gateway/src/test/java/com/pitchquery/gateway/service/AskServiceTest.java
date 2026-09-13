package com.pitchquery.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pitchquery.gateway.config.GatewayProperties;
import com.pitchquery.gateway.dto.AskResponse;
import com.pitchquery.gateway.dto.openai.ChatMessage;
import com.pitchquery.gateway.dto.openai.FunctionCall;
import com.pitchquery.gateway.dto.openai.ToolCall;
import com.pitchquery.gateway.dto.openai.ToolDefinition;
import com.pitchquery.gateway.dto.scraper.ScraperSnapshot;
import com.pitchquery.gateway.exception.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the OpenAI tool-calling loop, with both the OpenAI client and
 * scraper-service client mocked -- no real network calls in these tests.
 */
class AskServiceTest {

    private OpenAiClient openAiClient;
    private ScraperServiceClient scraperServiceClient;
    private AskService askService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        openAiClient = mock(OpenAiClient.class);
        scraperServiceClient = mock(ScraperServiceClient.class);
        objectMapper = new ObjectMapper();
        GatewayProperties properties = new GatewayProperties();
        properties.getToolLoop().setMaxIterations(5);
        askService = new AskService(openAiClient, scraperServiceClient, objectMapper, properties);
    }

    @Test
    void happyPath_callsToolThenReturnsValidatedResponse() {
        ScraperSnapshot snapshot = new ScraperSnapshot(
                "premier-league", "standings", "2025-26", "2026-09-13T18:30:00Z",
                42, false, standingsData());
        when(scraperServiceClient.fetchFreshData("premier-league", "standings")).thenReturn(snapshot);

        ChatMessage toolCallMessage = ChatMessage.assistant(null, List.of(
                new ToolCall("call_1", "function",
                        new FunctionCall("get_latest_espn_data", "{\"league\":\"premier-league\",\"data_type\":\"standings\"}"))));
        String finalJson = """
                {"answer_type":"standings","league":"premier-league",
                 "summary":"Arsenal are 3rd with 11 points from 5 games.",
                 "data":{"table":[{"rank":3,"team":"Arsenal"}]},
                 "generated_at":"ignored-by-server"}
                """;
        ChatMessage finalMessage = ChatMessage.assistant(finalJson, null);

        when(openAiClient.chatCompletion(anyList(), anyList()))
                .thenReturn(toolCallMessage)
                .thenReturn(finalMessage);

        AskResponse response = askService.ask("Where do Arsenal stand?");

        assertThat(response.answer_type()).isEqualTo("standings");
        assertThat(response.league()).isEqualTo("premier-league");
        assertThat(response.summary()).contains("Arsenal");
        assertThat(response.data()).isNotNull();
        assertThat(response.generated_at()).isNotBlank();
        // generated_at must be stamped by the server, never trusted from the LLM
        assertThat(response.generated_at()).isNotEqualTo("ignored-by-server");

        verify(scraperServiceClient, times(1)).fetchFreshData("premier-league", "standings");
    }

    @Test
    void toolResultFedBackToLlm_containsDataScrapedAtAndSeason() {
        ScraperSnapshot snapshot = new ScraperSnapshot(
                "premier-league", "fixtures", "2025-26", "2026-09-13T18:30:00Z",
                10, false, objectMapper.createObjectNode().put("fixtures", "stub"));
        when(scraperServiceClient.fetchFreshData("premier-league", "fixtures")).thenReturn(snapshot);

        ChatMessage toolCallMessage = ChatMessage.assistant(null, List.of(
                new ToolCall("call_1", "function",
                        new FunctionCall("get_latest_espn_data", "{\"league\":\"premier-league\",\"data_type\":\"fixtures\"}"))));
        ChatMessage finalMessage = ChatMessage.assistant(
                "{\"answer_type\":\"fixtures\",\"league\":\"premier-league\",\"summary\":\"ok\","
                        + "\"data\":{\"fixtures\":[]},\"generated_at\":\"x\"}", null);

        when(openAiClient.chatCompletion(anyList(), anyList()))
                .thenReturn(toolCallMessage)
                .thenReturn(finalMessage);

        askService.ask("What are the upcoming fixtures?");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(openAiClient, times(2)).chatCompletion(messagesCaptor.capture(), anyList());

        List<ChatMessage> secondCallMessages = messagesCaptor.getAllValues().get(1);
        ChatMessage toolResult = secondCallMessages.get(secondCallMessages.size() - 1);
        assertThat(toolResult.role()).isEqualTo("tool");
        assertThat(toolResult.toolCallId()).isEqualTo("call_1");
        assertThat(toolResult.content()).contains("\"scraped_at\":\"2026-09-13T18:30:00Z\"");
        assertThat(toolResult.content()).contains("\"season\":\"2025-26\"");
    }

    @Test
    void textAnswer_doesNotCallToolAndAllowsNullData() {
        ChatMessage finalMessage = ChatMessage.assistant(
                "{\"answer_type\":\"text\",\"league\":null,\"summary\":\"Arsenal's manager is Mikel Arteta.\","
                        + "\"data\":null,\"generated_at\":\"x\"}", null);
        when(openAiClient.chatCompletion(anyList(), anyList())).thenReturn(finalMessage);

        AskResponse response = askService.ask("Who is Arsenal's manager?");

        assertThat(response.answer_type()).isEqualTo("text");
        assertThat(response.league()).isNull();
        assertThat(response.data()).isNull();
        verify(scraperServiceClient, times(0)).fetchFreshData(any(), any());
    }

    @Test
    void malformedFinalJson_retriesOnceThenFallsBackToTextAnswer() {
        ChatMessage badMessage = ChatMessage.assistant("this is not json at all", null);
        ChatMessage badRetryMessage = ChatMessage.assistant("still not json", null);
        when(openAiClient.chatCompletion(anyList(), anyList()))
                .thenReturn(badMessage)
                .thenReturn(badRetryMessage);

        AskResponse response = askService.ask("Some question");

        assertThat(response.answer_type()).isEqualTo("text");
        assertThat(response.data()).isNull();
        assertThat(response.summary()).isNotBlank();
        assertThat(response.generated_at()).isNotBlank();
        verify(openAiClient, times(2)).chatCompletion(anyList(), anyList());
    }

    @Test
    void malformedFinalJson_retrySucceeds_returnsValidatedResponse() {
        ChatMessage badMessage = ChatMessage.assistant("not json", null);
        ChatMessage goodRetry = ChatMessage.assistant(
                "{\"answer_type\":\"text\",\"league\":null,\"summary\":\"fixed on retry\","
                        + "\"data\":null,\"generated_at\":\"x\"}", null);
        when(openAiClient.chatCompletion(anyList(), anyList()))
                .thenReturn(badMessage)
                .thenReturn(goodRetry);

        AskResponse response = askService.ask("Some question");

        assertThat(response.answer_type()).isEqualTo("text");
        assertThat(response.summary()).isEqualTo("fixed on retry");
    }

    @Test
    void missingRequiredField_isTreatedAsInvalid() {
        // "league" key entirely missing -> validation must fail -> fallback path
        ChatMessage missingLeague = ChatMessage.assistant(
                "{\"answer_type\":\"text\",\"summary\":\"no league key\",\"data\":null}", null);
        when(openAiClient.chatCompletion(anyList(), anyList()))
                .thenReturn(missingLeague)
                .thenReturn(missingLeague);

        AskResponse response = askService.ask("Some question");

        assertThat(response.answer_type()).isEqualTo("text");
        assertThat(response.summary()).isNotEqualTo("no league key");
    }

    @Test
    void structuredAnswerType_withNullData_isInvalidAndFallsBack() {
        ChatMessage invalid = ChatMessage.assistant(
                "{\"answer_type\":\"standings\",\"league\":\"premier-league\",\"summary\":\"oops\",\"data\":null}", null);
        when(openAiClient.chatCompletion(anyList(), anyList()))
                .thenReturn(invalid)
                .thenReturn(invalid);

        AskResponse response = askService.ask("Some question");

        assertThat(response.answer_type()).isEqualTo("text");
    }

    @Test
    void unknownAnswerType_isInvalidAndFallsBack() {
        ChatMessage invalid = ChatMessage.assistant(
                "{\"answer_type\":\"weather\",\"league\":null,\"summary\":\"oops\",\"data\":null}", null);
        when(openAiClient.chatCompletion(anyList(), anyList()))
                .thenReturn(invalid)
                .thenReturn(invalid);

        AskResponse response = askService.ask("Some question");

        assertThat(response.answer_type()).isEqualTo("text");
    }

    @Test
    void markdownFencedJson_isStillParsed() {
        ChatMessage fenced = ChatMessage.assistant(
                "```json\n{\"answer_type\":\"text\",\"league\":null,\"summary\":\"fenced ok\",\"data\":null,\"generated_at\":\"x\"}\n```",
                null);
        when(openAiClient.chatCompletion(anyList(), anyList())).thenReturn(fenced);

        AskResponse response = askService.ask("Some question");

        assertThat(response.answer_type()).isEqualTo("text");
        assertThat(response.summary()).isEqualTo("fenced ok");
    }

    @Test
    void blankQuestion_throwsBadRequestApiException() {
        assertThatThrownBy(() -> askService.ask("   "))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(400));
    }

    @Test
    void scraperServiceFailure_duringToolCall_propagatesAsApiException() {
        when(scraperServiceClient.fetchFreshData(any(), any()))
                .thenThrow(new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                        "scraper-service is unreachable"));

        ChatMessage toolCallMessage = ChatMessage.assistant(null, List.of(
                new ToolCall("call_1", "function",
                        new FunctionCall("get_latest_espn_data", "{\"league\":\"premier-league\",\"data_type\":\"standings\"}"))));
        when(openAiClient.chatCompletion(anyList(), anyList())).thenReturn(toolCallMessage);

        assertThatThrownBy(() -> askService.ask("Where do Arsenal stand?"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(502));
    }

    @Test
    void toolLoopExceedsMaxIterations_throwsApiException() {
        GatewayProperties properties = new GatewayProperties();
        properties.getToolLoop().setMaxIterations(2);
        AskService limitedService = new AskService(openAiClient, scraperServiceClient, objectMapper, properties);

        ChatMessage toolCallMessage = ChatMessage.assistant(null, List.of(
                new ToolCall("call_1", "function",
                        new FunctionCall("get_latest_espn_data", "{\"league\":\"premier-league\",\"data_type\":\"standings\"}"))));
        when(scraperServiceClient.fetchFreshData(any(), any())).thenReturn(
                new ScraperSnapshot("premier-league", "standings", "2025-26", "now", 1, false,
                        objectMapper.createObjectNode()));
        when(openAiClient.chatCompletion(anyList(), anyList())).thenReturn(toolCallMessage);

        assertThatThrownBy(() -> limitedService.ask("Where do Arsenal stand?"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(502));
    }

    @Test
    void toolDefinitionSentToOpenAi_matchesPlanSchema() {
        ChatMessage finalMessage = ChatMessage.assistant(
                "{\"answer_type\":\"text\",\"league\":null,\"summary\":\"ok\",\"data\":null,\"generated_at\":\"x\"}", null);
        when(openAiClient.chatCompletion(anyList(), anyList())).thenReturn(finalMessage);

        askService.ask("Some question");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ToolDefinition>> toolsCaptor = ArgumentCaptor.forClass(List.class);
        verify(openAiClient).chatCompletion(anyList(), toolsCaptor.capture());

        List<ToolDefinition> tools = toolsCaptor.getValue();
        assertThat(tools).hasSize(1);
        ToolDefinition.FunctionDefinition function = tools.get(0).function();
        assertThat(function.name()).isEqualTo("get_latest_espn_data");
        assertThat(function.parameters().get("required").toString()).contains("league").contains("data_type");
        assertThat(function.parameters().get("properties").get("league").get("enum").toString())
                .isEqualTo("[\"premier-league\"]");
        assertThat(function.parameters().get("properties").get("data_type").get("enum").toString())
                .isEqualTo("[\"standings\",\"fixtures\",\"results\"]");
    }

    private ObjectNode standingsData() {
        ObjectNode data = objectMapper.createObjectNode();
        data.putArray("table");
        return data;
    }
}
