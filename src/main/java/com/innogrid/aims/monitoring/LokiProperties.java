package com.innogrid.aims.monitoring;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "loki")
public class LokiProperties {

    private String baseUrl = "http://localhost:3100";

    public String getBaseUrl() { return baseUrl; }

    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
}
