package com.pitchquery.gateway.dto.openai;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** Request body for {@code POST /v1/chat/completions}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatCompletionRequest(
        String model,
        List<ChatMessage> messages,
        List<ToolDefinition> tools
) {
}
