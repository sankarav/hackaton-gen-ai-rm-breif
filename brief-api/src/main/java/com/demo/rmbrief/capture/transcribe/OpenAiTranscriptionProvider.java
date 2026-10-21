package com.demo.rmbrief.capture.transcribe;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Hosted OpenAI transcription. Enable with app.transcription.provider=openai and OPENAI_API_KEY.
 * Audio leaves the bank network with this provider — a compliance decision, not just a config one.
 */
@Component
@ConditionalOnProperty(name = "app.transcription.provider", havingValue = "openai")
public class OpenAiTranscriptionProvider implements TranscriptionProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)   // the JDK's h2c upgrade drops multipart bodies on uvicorn
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final String language;

    public OpenAiTranscriptionProvider(@Value("${app.transcription.openai.base-url}") String baseUrl,
                                       @Value("${app.transcription.openai.api-key}") String apiKey,
                                       @Value("${app.transcription.openai.model}") String model,
                                       @Value("${app.transcription.language}") String language) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("app.transcription.provider=openai requires OPENAI_API_KEY");
        }
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey;
        this.model = model;
        this.language = language;
    }

    @Override
    public String name() {
        return "openai:" + model;
    }

    @Override
    public Transcript transcribe(byte[] audio, String contentType, String filename) {
        // verbose_json gives segment timestamps, which the review UI uses for source tracing.
        MultipartBody body = new MultipartBody()
                .file("file", filename, contentType, audio)
                .field("model", model)
                .field("language", language)
                .field("response_format", "verbose_json")
                .field("timestamp_granularities[]", "segment");
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/audio/transcriptions"))
                .timeout(Duration.ofMinutes(10))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", body.contentType())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.build()))
                .build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            if (code / 100 != 2) {
                throw new TranscriptionException("OpenAI transcription returned HTTP " + code, code == 429 || code >= 500);
            }
            return LocalWhisperProvider.parse(MAPPER.readTree(resp.body()));
        } catch (IOException e) {
            throw new TranscriptionException("OpenAI transcription unreachable: " + e.getMessage(), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TranscriptionException("Interrupted", true);
        }
    }
}
