package com.smartinbox.gateway;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PracticeGatewayRoutes {
    @Bean
    public RouteLocator practiceRoutes(RouteLocatorBuilder builder) {
        return builder.routes().route("smart-practice", route -> route
                .path("/api/practice", "/api/practice/**")
                .uri("http://127.0.0.1:8083")).build();
    }
}
