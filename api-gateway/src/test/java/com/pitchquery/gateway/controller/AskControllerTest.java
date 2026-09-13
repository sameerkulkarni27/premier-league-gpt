package com.pitchquery.gateway.controller;

import com.pitchquery.gateway.dto.openai.ChatMessage;
import com.pitchquery.gateway.dto.openai.FunctionCall;
import com.pitchquery.gateway.dto.openai.ToolCall;
import com.pitchquery.gateway.dto.scraper.ScraperSnapshot;
import com.pitchquery.gateway.exception.ApiException;
import com.pitchquery.gateway.service.OpenAiClient;
import com.pitchquery.gateway.service.ScraperServiceClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack test of {@code POST /api/ask} through the real Spring context,
 * with the OpenAI and scraper-service clients replaced by Mockito mocks
 * (@MockBean) -- no real OpenAI or scraper-service calls.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AskControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OpenAiClient openAiClient;

    @MockBean
    private ScraperServiceClient scraperServiceClient;

    @Test
    void happyPath_returnsDocumentedEnvelope() throws Exception {
        when(scraperServiceClient.fetchFreshData(eq("premier-league"), eq("standings")))
                .thenReturn(new ScraperSnapshot("premier-league", "standings", "2025-26",
                        "2026-09-13T18:30:00Z", 10, false,
                        new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"table\":[]}")));

        ChatMessage toolCallMessage = ChatMessage.assistant(null, List.of(
                new ToolCall("call_1", "function",
                        new FunctionCall("get_latest_espn_data", "{\"league\":\"premier-league\",\"data_type\":\"standings\"}"))));
        ChatMessage finalMessage = ChatMessage.assistant(
                "{\"answer_type\":\"standings\",\"league\":\"premier-league\",\"summary\":\"Arsenal are 3rd.\","
                        + "\"data\":{\"table\":[]},\"generated_at\":\"ignored\"}", null);
        when(openAiClient.chatCompletion(anyList(), anyList()))
                .thenReturn(toolCallMessage)
                .thenReturn(finalMessage);

        mockMvc.perform(post("/api/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Where do Arsenal stand?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer_type").value("standings"))
                .andExpect(jsonPath("$.league").value("premier-league"))
                .andExpect(jsonPath("$.summary").value("Arsenal are 3rd."))
                .andExpect(jsonPath("$.data.table").isArray())
                .andExpect(jsonPath("$.generated_at").exists());
    }

    @Test
    void blankQuestion_returns400WithErrorBody() throws Exception {
        mockMvc.perform(post("/api/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void missingBody_returns400WithErrorBody() throws Exception {
        mockMvc.perform(post("/api/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void scraperServiceUnreachable_returns502WithErrorBody() throws Exception {
        when(scraperServiceClient.fetchFreshData(eq("premier-league"), eq("standings")))
                .thenThrow(new ApiException(HttpStatus.BAD_GATEWAY, "scraper-service is unreachable (needed for premier-league/standings)"));

        ChatMessage toolCallMessage = ChatMessage.assistant(null, List.of(
                new ToolCall("call_1", "function",
                        new FunctionCall("get_latest_espn_data", "{\"league\":\"premier-league\",\"data_type\":\"standings\"}"))));
        when(openAiClient.chatCompletion(anyList(), anyList())).thenReturn(toolCallMessage);

        mockMvc.perform(post("/api/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Where do Arsenal stand?\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Exception"))));
    }

    @Test
    void malformedLlmOutput_fallsBackTo200TextAnswerInsteadOf500() throws Exception {
        ChatMessage badMessage = ChatMessage.assistant("not valid json whatsoever", null);
        when(openAiClient.chatCompletion(anyList(), anyList()))
                .thenReturn(badMessage)
                .thenReturn(badMessage);

        mockMvc.perform(post("/api/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Some question\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer_type").value("text"))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
    }
}
