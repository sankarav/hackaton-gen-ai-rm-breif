package com.demo.rmbrief.config;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * The single Anthropic client used by every Claude call. Endpoint and auth come from
 * {@code app.anthropic.*}, so a corporate gateway is a profile switch, not a code change.
 */
@Configuration
@EnableConfigurationProperties(AnthropicConfig.Props.class)
public class AnthropicConfig {

    private static final Logger log = LoggerFactory.getLogger(AnthropicConfig.class);

    /**
     * @param apiKeyHeader blank = standard {@code x-api-key}; otherwise the key is sent only in this header
     * @param headers      extra static headers added to every request (e.g. an app id the gateway requires)
     */
    @ConfigurationProperties("app.anthropic")
    public record Props(String baseUrl, String apiKey, String apiKeyHeader, Map<String, String> headers) {}

    @Bean(destroyMethod = "close")
    public AnthropicClient anthropicClient(Props props) {
        if (props.apiKey() == null || props.apiKey().isBlank()) {
            throw new IllegalStateException("app.anthropic.api-key is empty — set ANTHROPIC_API_KEY (or the gateway key for the gateway profile)");
        }
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder().baseUrl(props.baseUrl());

        boolean customHeader = props.apiKeyHeader() != null && !props.apiKeyHeader().isBlank();
        if (customHeader) {
            // Don't also set apiKey: the key must not leak to the gateway in x-api-key.
            builder.putHeader(props.apiKeyHeader(), props.apiKey());
        } else {
            builder.apiKey(props.apiKey());
        }
        if (props.headers() != null) props.headers().forEach(builder::putHeader);

        log.info("[anthropic] baseUrl={} auth={} extraHeaders={}", props.baseUrl(),
                customHeader ? "header " + props.apiKeyHeader() : "x-api-key",
                props.headers() == null ? "[]" : props.headers().keySet());
        return builder.build();
    }
}
