package com.pitchquery.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.pitchquery.gateway.config.GatewayProperties;
import com.pitchquery.gateway.dto.scraper.ScraperSnapshot;
import com.pitchquery.gateway.exception.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for the api-gateway <-> scraper-service HTTP contract
 * (PLAN.md), against a WireMock server standing in for the real
 * scraper-service -- no real scraper-service in these tests.
 */
class HttpScraperServiceClientTest {

    private WireMockServer wireMockServer;
    private HttpScraperServiceClient client;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(0);
        wireMockServer.start();
        WireMock.configureFor("localhost", wireMockServer.port());

        GatewayProperties properties = new GatewayProperties();
        properties.getScraper().setBaseUrl("http://localhost:" + wireMockServer.port());
        properties.getScraper().setMaxAgeSeconds(300);

        client = new HttpScraperServiceClient(RestClient.builder(), properties, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    @Test
    void freshCacheHit_returnsLatestWithoutRefreshing() {
        wireMockServer.stubFor(get(urlPathMatching("/internal/espn/premier-league/standings/latest"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"league":"premier-league","data_type":"standings","season":"2025-26",
                                 "scraped_at":"2026-09-13T18:30:00Z","age_seconds":42,"stale":false,
                                 "data":{"table":[]}}
                                """)));

        ScraperSnapshot result = client.fetchFreshData("premier-league", "standings");

        assertThat(result.season()).isEqualTo("2025-26");
        assertThat(result.isStale()).isFalse();
        wireMockServer.verify(0, com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(
                urlPathMatching("/internal/espn/.*/refresh")));
    }

    @Test
    void cacheMiss404_fallsBackToRefresh() {
        wireMockServer.stubFor(get(urlPathMatching("/internal/espn/premier-league/standings/latest"))
                .willReturn(aResponse().withStatus(404).withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"not_found\",\"league\":\"premier-league\",\"data_type\":\"standings\"}")));
        wireMockServer.stubFor(post(urlPathMatching("/internal/espn/premier-league/standings/refresh"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"league":"premier-league","data_type":"standings","season":"2025-26",
                                 "scraped_at":"2026-09-13T19:00:00Z","age_seconds":0,"stale":false,
                                 "data":{"table":[{"rank":1,"team":"Liverpool"}]}}
                                """)));

        ScraperSnapshot result = client.fetchFreshData("premier-league", "standings");

        assertThat(result.scraped_at()).isEqualTo("2026-09-13T19:00:00Z");
        assertThat(result.data().get("table")).hasSize(1);
    }

    @Test
    void staleCacheHit_fallsBackToRefresh() {
        wireMockServer.stubFor(get(urlPathMatching("/internal/espn/premier-league/fixtures/latest"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"league":"premier-league","data_type":"fixtures","season":"2025-26",
                                 "scraped_at":"2026-09-13T10:00:00Z","age_seconds":9000,"stale":true,
                                 "data":{"fixtures":[]}}
                                """)));
        wireMockServer.stubFor(post(urlPathMatching("/internal/espn/premier-league/fixtures/refresh"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"league":"premier-league","data_type":"fixtures","season":"2025-26",
                                 "scraped_at":"2026-09-13T19:00:00Z","age_seconds":0,"stale":false,
                                 "data":{"fixtures":[{"event_id":"1"}]}}
                                """)));

        ScraperSnapshot result = client.fetchFreshData("premier-league", "fixtures");

        assertThat(result.isStale()).isFalse();
        assertThat(result.scraped_at()).isEqualTo("2026-09-13T19:00:00Z");
    }

    @Test
    void refreshScrapeFailed502_propagatesAsApiExceptionBadGateway() {
        wireMockServer.stubFor(get(urlPathMatching("/internal/espn/premier-league/results/latest"))
                .willReturn(aResponse().withStatus(404)));
        wireMockServer.stubFor(post(urlPathMatching("/internal/espn/premier-league/results/refresh"))
                .willReturn(aResponse().withStatus(502).withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"scrape_failed\",\"league\":\"premier-league\","
                                + "\"data_type\":\"results\",\"detail\":\"ESPN timed out\"}")));

        assertThatThrownBy(() -> client.fetchFreshData("premier-league", "results"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(502));
    }

    @Test
    void scraperServiceDown_propagatesAsApiExceptionBadGateway() {
        wireMockServer.stop(); // simulate scraper-service being completely unreachable

        assertThatThrownBy(() -> client.fetchFreshData("premier-league", "standings"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(502));
    }
}
