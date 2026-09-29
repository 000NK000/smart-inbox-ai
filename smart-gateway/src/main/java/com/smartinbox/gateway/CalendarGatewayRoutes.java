package com.smartinbox.gateway;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Keep the local calendar route separate from machine-specific account configuration. */
@Configuration
public class CalendarGatewayRoutes {
    @Bean
    public RouteLocator calendarRoutes(RouteLocatorBuilder builder) {
        return builder.routes().route("smart-calendar", route -> route
                .path("/api/calendar", "/api/calendar/**")
                .uri("http://127.0.0.1:8083")).build();
    }
}
