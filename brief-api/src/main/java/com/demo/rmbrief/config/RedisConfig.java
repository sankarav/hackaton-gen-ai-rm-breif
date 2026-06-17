package com.demo.rmbrief.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;

/**
 * Redis caching is activated only when app.cache.enabled=true.
 * Without this, the Redis container starts but the app never acquires a connection.
 */
@Configuration
@EnableCaching
@ConditionalOnProperty(name = "app.cache.enabled", havingValue = "true")
public class RedisConfig {
    // RedisConnectionFactory and RedisTemplate are auto-configured by
    // spring-boot-starter-data-redis when this class is active.
}
