package com.pitchquery.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Binds the {@code pitchquery.*} configuration tree (see application.yml).
 * All values are overridable via environment variables per PLAN.md
 * (OPENAI_API_KEY, OPENAI_MODEL, SCRAPER_SERVICE_BASE_URL, ...).
 */
@ConfigurationProperties(prefix = "pitchquery")
public class GatewayProperties {

    @NestedConfigurationProperty
    private final OpenAi openai = new OpenAi();

    @NestedConfigurationProperty
    private final Scraper scraper = new Scraper();

    @NestedConfigurationProperty
    private final ToolLoop toolLoop = new ToolLoop();

    public OpenAi getOpenai() {
        return openai;
    }

    public Scraper getScraper() {
        return scraper;
    }

    public ToolLoop getToolLoop() {
        return toolLoop;
    }

    public static class OpenAi {
        private String apiKey;
        private String model;
        private String baseUrl;

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }

    public static class Scraper {
        private String baseUrl;
        private int maxAgeSeconds;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public int getMaxAgeSeconds() {
            return maxAgeSeconds;
        }

        public void setMaxAgeSeconds(int maxAgeSeconds) {
            this.maxAgeSeconds = maxAgeSeconds;
        }
    }

    public static class ToolLoop {
        private int maxIterations = 5;

        public int getMaxIterations() {
            return maxIterations;
        }

        public void setMaxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
        }
    }
}
