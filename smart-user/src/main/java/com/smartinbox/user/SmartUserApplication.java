package com.smartinbox.user;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication
@EnableDiscoveryClient
public class SmartUserApplication {
    public static void main(String[] args) {
        SpringApplication.run(SmartUserApplication.class, args);
    }
}
