package com.pitchquery.gateway.service;

import com.pitchquery.gateway.dto.openai.ChatMessage;
import com.pitchquery.gateway.dto.openai.ToolDefinition;

import java.util.List;

/**
 * Thin seam over the OpenAI chat-completions call so tests can mock the LLM
 * entirely (no real network calls to OpenAI in tests).
 */
public interface OpenAiClient {

    /**
     * Sends the conversation so far (+ tool definitions) and returns the
     * assistant's reply message (which may itself carry tool calls).
     *
     * @throws com.pitchquery.gateway.exception.ApiException on any OpenAI
     *         transport/API failure.
     */
    ChatMessage chatCompletion(List<ChatMessage> messages, List<ToolDefinition> tools);
}
