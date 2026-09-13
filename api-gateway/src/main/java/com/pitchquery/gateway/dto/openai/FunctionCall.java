package com.pitchquery.gateway.dto.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The function name + stringified-JSON arguments the model wants to invoke.
 * {@code arguments} is a raw JSON string per the OpenAI API, not a parsed node.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FunctionCall(String name, String arguments) {
}
