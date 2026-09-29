package com.smartinbox.processor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication
@EnableConfigurationProperties(RedisProperties.class)
@EnableDiscoveryClient
public class SmartProcessorApplication {
    public static void main(String[] args) {
        SpringApplication.run(SmartProcessorApplication.class, args);
    }

    @org.springframework.context.annotation.Bean
    public org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory redisConnectionFactory(RedisProperties properties) {
        org.springframework.data.redis.connection.RedisStandaloneConfiguration config = new org.springframework.data.redis.connection.RedisStandaloneConfiguration(
                properties.getHost(), properties.getPort());
        config.setDatabase(properties.getDatabase());
        if (properties.getUsername() != null && !properties.getUsername().isEmpty()) {
            config.setUsername(properties.getUsername());
        }
        if (properties.getPassword() != null && !properties.getPassword().isEmpty()) {
            config.setPassword(properties.getPassword());
        }

        // FORCE RESP2 for passwordless Redis default compatibility
        io.lettuce.core.ClientOptions clientOptions = io.lettuce.core.ClientOptions.builder()
                .protocolVersion(io.lettuce.core.protocol.ProtocolVersion.RESP2)
                .build();

        org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration clientConfig = org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration
                .builder()
                .commandTimeout(java.time.Duration.ofSeconds(60))
                .clientOptions(clientOptions)
                .build();

        return new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(config, clientConfig);
    }

    @org.springframework.context.annotation.Bean
    public org.springframework.boot.web.client.RestClientCustomizer restClientCustomizer() {
        return restClientBuilder -> restClientBuilder
                .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory() {
                    {
                        setConnectTimeout(60000); // 60s
                        setReadTimeout(300000); // 300s
                    }
                });
    }

    @org.springframework.context.annotation.Bean
    public org.springframework.boot.CommandLineRunner commandLineRunner(org.springframework.core.env.Environment env,
            org.springframework.data.redis.core.StringRedisTemplate redisTemplate) {
        return args -> {
            System.out.println("==========================================");
            System.out.println("   REDIS DIAGNOSTICS (MANUAL BEAN)");
            System.out.println("==========================================");
            try {
                String pong = redisTemplate.getConnectionFactory().getConnection().ping();
                System.out.println("PING Result: " + pong);

                System.out.println("Testing SET/GET...");
                redisTemplate.opsForValue().set("startup_test", "success");
                String value = redisTemplate.opsForValue().get("startup_test");
                System.out.println("GET Result: " + value);

                if ("success".equals(value)) {
                    System.out.println(">>> REDIS IS FULLY FUNCTIONAL <<<");
                }
            } catch (Exception e) {
                System.err.println("REDIS OPERATIONAL TEST FAILED: " + e.getMessage());
                e.printStackTrace();
            }
            System.out.println("==========================================");
        };
    }
}
