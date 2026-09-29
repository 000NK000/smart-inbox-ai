package com.smartinbox.processor;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;

class RedisConfigurationTest {
    @Test void springPropertiesControlHostPortDatabaseAndAclCredentials() {
        var environment = new MockEnvironment()
                .withProperty("spring.data.redis.host", "redis.example.test")
                .withProperty("spring.data.redis.port", "16380")
                .withProperty("spring.data.redis.database", "4")
                .withProperty("spring.data.redis.username", "test-only-user")
                .withProperty("spring.data.redis.password", "test-only-redis-password");
        RedisProperties properties = Binder.get(environment)
                .bind("spring.data.redis", Bindable.of(RedisProperties.class)).get();
        var factory = new SmartProcessorApplication().redisConnectionFactory(properties);
        var configuration = factory.getStandaloneConfiguration();
        assertEquals("redis.example.test", configuration.getHostName());
        assertEquals(16380, configuration.getPort());
        assertEquals(4, configuration.getDatabase());
        assertEquals("test-only-user", configuration.getUsername());
        assertEquals("test-only-redis-password", new String(configuration.getPassword().get()));
    }

    @Test void absentAndEmptyPasswordsDoNotInventCredentials() {
        RedisProperties properties = new RedisProperties();
        var absent = new SmartProcessorApplication().redisConnectionFactory(properties);
        assertFalse(absent.getStandaloneConfiguration().getPassword().isPresent());
        properties.setPassword("");
        var empty = new SmartProcessorApplication().redisConnectionFactory(properties);
        assertFalse(empty.getStandaloneConfiguration().getPassword().isPresent());
    }
}
