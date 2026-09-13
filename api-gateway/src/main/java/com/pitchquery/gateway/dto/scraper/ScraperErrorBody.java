package com.pitchquery.gateway.dto.scraper;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Error body shape returned by scraper-service on 400/404/502, e.g.
 * {@code {"error": "not_found", "league": "...", "data_type": "..."}} or
 * {@code {"error": "scrape_failed", "detail": "..."}}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScraperErrorBody(String error, String league, String data_type, String detail) {
}
