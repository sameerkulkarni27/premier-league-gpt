package com.pitchquery.gateway.controller;

import com.pitchquery.gateway.dto.AskRequest;
import com.pitchquery.gateway.dto.AskResponse;
import com.pitchquery.gateway.exception.ApiException;
import com.pitchquery.gateway.service.AskService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** {@code POST /api/ask} -- see PLAN.md "Contract: frontend <-> api-gateway". */
@RestController
public class AskController {

    private final AskService askService;

    public AskController(AskService askService) {
        this.askService = askService;
    }

    @PostMapping(path = "/api/ask", produces = MediaType.APPLICATION_JSON_VALUE)
    public AskResponse ask(@RequestBody(required = false) AskRequest request) {
        if (request == null || request.question() == null || request.question().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "\"question\" is required");
        }
        return askService.ask(request.question());
    }
}
