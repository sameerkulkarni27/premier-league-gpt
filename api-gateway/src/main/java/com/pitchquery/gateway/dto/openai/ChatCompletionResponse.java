package com.pitchquery.gateway.dto.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Response body for {@code POST /v1/chat/completions}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatCompletionResponse(List<Choice> choices) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(ChatMessage message, String finish_reason) {
    }
}
