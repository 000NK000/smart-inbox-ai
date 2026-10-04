package com.smartinbox.gateway;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StocksGatewayRoutes {
    @Bean public RouteLocator stocksRoutes(RouteLocatorBuilder builder) {
        return builder.routes().route("smart-stocks",r -> r.path("/api/stocks/**").uri("http://127.0.0.1:8083")).build();
    }
}
