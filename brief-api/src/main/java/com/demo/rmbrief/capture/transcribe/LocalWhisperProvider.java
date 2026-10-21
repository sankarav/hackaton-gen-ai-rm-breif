package com.demo.rmbrief.capture.transcribe;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.ArrayList;
import java.util.List;

/** Self-hosted Whisper (openai-whisper-asr-webservice container). Audio never leaves the network. */
@Component
@ConditionalOnProperty(name = "app.transcription.provider", havingValue = "local-whisper", matchIfMissing = true)
public class LocalWhisperProvider implements TranscriptionProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)   // the JDK's h2c upgrade drops multipart bodies on uvicorn
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final String baseUrl;
    private final String language;

    public LocalWhisperProvider(@Value("${app.transcription.local-whisper.url}") String baseUrl,
                                @Value("${app.transcription.language}") String language) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.language = language;
    }

    @Override
    public String name() {
        return "local-whisper";
    }

    @Override
    public Transcript transcribe(byte[] audio, String contentType, String filename) {
        MultipartBody body = new MultipartBody().file("audio_file", filename, contentType, audio);
        HttpRequest req = HttpRequest.newBuilder(URI.create(
                        baseUrl + "/asr?task=transcribe&output=json&encode=true&language=" + language))
                .timeout(Duration.ofMinutes(15))
                .header("Content-Type", body.contentType())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.build()))
                .build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new TranscriptionException("Whisper returned HTTP " + resp.statusCode(), resp.statusCode() >= 500);
            }
            return parse(MAPPER.readTree(resp.body()));
        } catch (IOException e) {
            throw new TranscriptionException("Whisper unreachable: " + e.getMessage(), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TranscriptionException("Interrupted", true);
        }
    }

    static Transcript parse(JsonNode root) {
        List<Segment> segments = new ArrayList<>();
        for (JsonNode s : root.path("segments")) {
            String text = s.path("text").asText("").trim();
            if (!text.isEmpty()) {
                segments.add(new Segment(s.path("start").asDouble(), s.path("end").asDouble(), text, null));
            }
        }
        if (segments.isEmpty() && !root.path("text").asText("").isBlank()) {
            segments.add(new Segment(0, 0, root.path("text").asText().trim(), null));
        }
        return new Transcript(segments);
    }
}
