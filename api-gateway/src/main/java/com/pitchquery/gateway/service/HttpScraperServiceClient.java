package com.pitchquery.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pitchquery.gateway.config.GatewayProperties;
import com.pitchquery.gateway.dto.scraper.ScraperErrorBody;
import com.pitchquery.gateway.dto.scraper.ScraperSnapshot;
import com.pitchquery.gateway.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Implements the api-gateway side of the contract in PLAN.md
 * ("Contract: api-gateway <-> scraper-service"): try {@code /latest} first,
 * fall back to {@code /refresh} on a 404 or a stale cache hit. The LLM never
 * sees this two-step dance -- it just gets fresh data back from one tool call.
 */
@Component
public class HttpScraperServiceClient implements ScraperServiceClient {

    private static final Logger log = LoggerFactory.getLogger(HttpScraperServiceClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final int maxAgeSeconds;

    public HttpScraperServiceClient(RestClient.Builder restClientBuilder,
                                     GatewayProperties properties,
                                     ObjectMapper objectMapper) {
        this.restClient = restClientBuilder.baseUrl(properties.getScraper().getBaseUrl()).build();
        this.objectMapper = objectMapper;
        this.maxAgeSeconds = properties.getScraper().getMaxAgeSeconds();
    }

    @Override
    public ScraperSnapshot fetchFreshData(String league, String dataType) {
        ScraperSnapshot latest = tryGetLatest(league, dataType);
        if (latest == null || latest.isStale()) {
            log.info("Cache miss/stale for {}/{}, refreshing", league, dataType);
            return refresh(league, dataType);
        }
        return latest;
    }

    private ScraperSnapshot tryGetLatest(String league, String dataType) {
        try {
            return restClient.get()
                    .uri("/internal/espn/{league}/{dataType}/latest?max_age_seconds={maxAge}",
                            league, dataType, maxAgeSeconds)
                    .exchange((request, response) -> {
                        if (response.getStatusCode().value() == 404) {
                            return null;
                        }
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            throw upstreamFailure("latest", league, dataType, response.getStatusCode().value(),
                                    readBody(response));
                        }
                        return objectMapper.readValue(response.getBody(), ScraperSnapshot.class);
                    }, false);
        } catch (ResourceAccessException ex) {
            throw unreachable(league, dataType, ex);
        }
    }

    private ScraperSnapshot refresh(String league, String dataType) {
        try {
            return restClient.post()
                    .uri("/internal/espn/{league}/{dataType}/refresh", league, dataType)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{}")
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            throw upstreamFailure("refresh", league, dataType, response.getStatusCode().value(),
                                    readBody(response));
                        }
                        return objectMapper.readValue(response.getBody(), ScraperSnapshot.class);
                    }, false);
        } catch (ResourceAccessException ex) {
            throw unreachable(league, dataType, ex);
        }
    }

    private String readBody(org.springframework.http.client.ClientHttpResponse response) {
        try {
            return StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return "";
        }
    }

    private ApiException upstreamFailure(String operation, String league, String dataType, int status, String body) {
        String detail = tryParseDetail(body);
        log.warn("scraper-service {} for {}/{} returned {}: {}", operation, league, dataType, status, body);
        return new ApiException(HttpStatus.BAD_GATEWAY,
                "scraper-service " + operation + " failed for " + league + "/" + dataType
                        + " (status " + status + (detail != null ? ", " + detail : "") + ")");
    }

    private String tryParseDetail(String body) {
        try {
            ScraperErrorBody parsed = objectMapper.readValue(body, ScraperErrorBody.class);
            return parsed.detail() != null ? parsed.detail() : parsed.error();
        } catch (IOException ex) {
            return null;
        }
    }

    private ApiException unreachable(String league, String dataType, Exception cause) {
        log.error("scraper-service unreachable while fetching {}/{}", league, dataType, cause);
        return new ApiException(HttpStatus.BAD_GATEWAY,
                "scraper-service is unreachable (needed for " + league + "/" + dataType + ")", cause);
    }
}
