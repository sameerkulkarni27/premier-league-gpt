package com.pitchquery.gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Response envelope for {@code POST /api/ask}, per PLAN.md
 * ("Contract: frontend <-> api-gateway").
 *
 * {@code generated_at} is always stamped by the server, never taken from
 * the LLM's output.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AskResponse(
        String answer_type,
        String league,
        String summary,
        JsonNode data,
        String generated_at
) {
    public static final String TYPE_STANDINGS = "standings";
    public static final String TYPE_FIXTURES = "fixtures";
    public static final String TYPE_RESULTS = "results";
    public static final String TYPE_TEXT = "text";
}
