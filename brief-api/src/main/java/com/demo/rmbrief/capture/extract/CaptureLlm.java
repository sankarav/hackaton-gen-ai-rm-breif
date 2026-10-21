package com.demo.rmbrief.capture.extract;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.TextBlockParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/** Schema-constrained Claude calls for the capture pipeline. Logs sizes and usage, never content. */
@Component
public class CaptureLlm {

    private static final Logger log = LoggerFactory.getLogger(CaptureLlm.class);

    private final AnthropicClient client = AnthropicOkHttpClient.fromEnv();
    private final String model;

    public CaptureLlm(@Value("${app.capture.model}") String model) {
        this.model = model;
    }

    public <T> T structured(String purpose, Class<T> type, String system, List<ContentBlockParam> userContent) {
        StructuredMessageCreateParams<T> params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(16000L)
                .system(system)
                .outputConfig(type)
                .addUserMessageOfBlockParams(userContent)
                // Route refused requests to a fallback model instead of failing the capture.
                .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .build();

        StructuredMessage<T> response = client.messages().create(params);
        StopReason stop = response.stopReason().orElse(null);
        if (StopReason.REFUSAL.equals(stop)) {
            throw new LlmRefusalException(purpose + " was declined by the model");
        }
        if (StopReason.MAX_TOKENS.equals(stop)) {
            throw new IllegalStateException(purpose + " output exceeded max_tokens");
        }
        var usage = response.rawMessage().usage();
        log.info("[llm] {} model={} in={} out={}", purpose, response.model().asString(),
                usage.inputTokens(), usage.outputTokens());

        return response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(typed -> typed.text())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(purpose + " returned no structured content"));
    }

    public static ContentBlockParam text(String s) {
        return ContentBlockParam.ofText(TextBlockParam.builder().text(s).build());
    }

    public static class LlmRefusalException extends RuntimeException {
        public LlmRefusalException(String message) {
            super(message);
        }
    }
}
