package com.pitchquery.gateway.dto;

/** Uniform error envelope: {@code { "error": "..." } } — never a raw stack trace. */
public record ErrorResponse(String error) {
}
