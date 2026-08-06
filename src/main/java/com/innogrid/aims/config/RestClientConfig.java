package com.innogrid.aims.config;

import com.innogrid.aims.cloudbarista.CloudBaristaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Cloud-Barista 컴포넌트(CB-Spider, CB-Tumblebug)와 통신하기 위한 RestClient 빈 설정.
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient spiderRestClient(CloudBaristaProperties props) {
        return RestClient.builder()
                .baseUrl(props.getSpider().getBaseUrl())
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Bean
    public RestClient tumblebugRestClient(CloudBaristaProperties props) {
        return RestClient.builder()
                .baseUrl(props.getTumblebug().getBaseUrl())
                .defaultHeaders(headers -> {
                    headers.setBasicAuth(
                            props.getTumblebug().getUsername(),
                            props.getTumblebug().getPassword()
                    );
                    headers.set("Content-Type", "application/json");
                })
                .build();
    }
}
