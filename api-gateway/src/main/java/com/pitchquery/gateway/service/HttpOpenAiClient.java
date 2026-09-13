package com.pitchquery.gateway.service;

import com.pitchquery.gateway.config.GatewayProperties;
import com.pitchquery.gateway.dto.openai.ChatCompletionRequest;
import com.pitchquery.gateway.dto.openai.ChatCompletionResponse;
import com.pitchquery.gateway.dto.openai.ChatMessage;
import com.pitchquery.gateway.dto.openai.ToolDefinition;
import com.pitchquery.gateway.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;

/** Calls OpenAI's {@code POST /v1/chat/completions} (chat-completions API, function calling). */
@Component
public class HttpOpenAiClient implements OpenAiClient {

    private static final Logger log = LoggerFactory.getLogger(HttpOpenAiClient.class);

    private final RestClient restClient;
    private final String model;

    public HttpOpenAiClient(RestClient.Builder restClientBuilder, GatewayProperties properties) {
        GatewayProperties.OpenAi openai = properties.getOpenai();
        if (openai.getApiKey() == null || openai.getApiKey().isBlank()) {
            log.warn("OPENAI_API_KEY is not set; calls to OpenAI will fail");
        }
        this.restClient = restClientBuilder
                .baseUrl(openai.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + openai.getApiKey())
                .build();
        this.model = openai.getModel();
    }

    @Override
    public ChatMessage chatCompletion(List<ChatMessage> messages, List<ToolDefinition> tools) {
        ChatCompletionRequest request = new ChatCompletionRequest(model, messages, tools);
        try {
            ChatCompletionResponse response = restClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(ChatCompletionResponse.class);

            if (response == null || response.choices() == null || response.choices().isEmpty()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "OpenAI returned no choices");
            }
            return response.choices().get(0).message();
        } catch (RestClientResponseException ex) {
            log.error("OpenAI API error: {} {}", ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "OpenAI API request failed with status " + ex.getStatusCode().value(), ex);
        } catch (ResourceAccessException ex) {
            log.error("OpenAI API unreachable", ex);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "OpenAI API is unreachable", ex);
        }
    }
}
