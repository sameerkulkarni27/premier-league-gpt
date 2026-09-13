package com.pitchquery.gateway.dto.scraper;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Successful (200) response body shape shared by both
 * {@code GET /internal/espn/<league>/<data_type>/latest} and
 * {@code POST /internal/espn/<league>/<data_type>/refresh}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScraperSnapshot(
        String league,
        String data_type,
        String season,
        String scraped_at,
        Integer age_seconds,
        Boolean stale,
        JsonNode data
) {
    public boolean isStale() {
        return Boolean.TRUE.equals(stale);
    }
}
