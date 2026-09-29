package com.smartinbox.gateway;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.*;

@Configuration
public class JobApplicationGatewayRoutes {
    @Bean public RouteLocator jobApplicationRoutes(RouteLocatorBuilder builder){return builder.routes().route("job-applications",route->route.path("/api/job-applications","/api/job-applications/**").uri("http://127.0.0.1:8083")).build();}
}
