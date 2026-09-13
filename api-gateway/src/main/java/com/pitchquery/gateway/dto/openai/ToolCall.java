package com.pitchquery.gateway.dto.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** A tool call requested by the assistant (OpenAI function-calling). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ToolCall(String id, String type, FunctionCall function) {
}
