package com.pitchquery.gateway.service;

import com.pitchquery.gateway.dto.scraper.ScraperSnapshot;

/**
 * Client for the api-gateway &lt;-&gt; scraper-service HTTP contract
 * (PLAN.md, "Contract: api-gateway <-> scraper-service").
 *
 * Implementations own the "fetch latest, refresh if missing/stale" dance;
 * callers (the tool executor) just get back fresh-enough data.
 */
public interface ScraperServiceClient {

    /**
     * Returns fresh-enough data for {@code league}/{@code dataType}: reads
     * the cache via {@code /latest} first, falling back to {@code /refresh}
     * when the cache entry is missing (404) or stale.
     *
     * @throws com.pitchquery.gateway.exception.ApiException if scraper-service
     *         is unreachable, or a refresh fails (502) or is rejected (400).
     */
    ScraperSnapshot fetchFreshData(String league, String dataType);
}
