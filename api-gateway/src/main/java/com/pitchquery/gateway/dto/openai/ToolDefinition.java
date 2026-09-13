package com.pitchquery.gateway.dto.openai;

import com.fasterxml.jackson.databind.JsonNode;

/** A tool exposed to the model, per OpenAI's function-calling schema. */
public record ToolDefinition(String type, FunctionDefinition function) {

    public static ToolDefinition function(FunctionDefinition function) {
        return new ToolDefinition("function", function);
    }

    public record FunctionDefinition(String name, String description, JsonNode parameters) {
    }
}
