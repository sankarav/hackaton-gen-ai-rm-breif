package com.demo.rmbrief.web;

import com.demo.rmbrief.agent.AgentOrchestrator;
import com.demo.rmbrief.agent.StepEmitter;
import com.demo.rmbrief.delta.ChangeReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * POST /briefs/{clientId}   — kick off brief generation (202)
 * GET  /briefs/{clientId}/stream — SSE stream of agent steps + delta + final brief
 */
@RestController
public class BriefController {

    private static final Logger log = LoggerFactory.getLogger(BriefController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .findAndRegisterModules()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final AgentOrchestrator orchestrator;

    public BriefController(AgentOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    /** Returns 202 with the stream URL so the UI knows where to connect. */
    @PostMapping("/briefs/{clientId}")
    public ResponseEntity<Map<String, String>> startBrief(@PathVariable String clientId) {
        return ResponseEntity.accepted().body(Map.of(
            "clientId", clientId,
            "streamUrl", "/briefs/" + clientId + "/stream",
            "message", "Brief generation ready. Connect to streamUrl for live updates."
        ));
    }

    /** SSE stream — starts the agent and flushes events as they happen. */
    @GetMapping(value = "/briefs/{clientId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamBrief(@PathVariable String clientId) {
        log.info("[brief] SSE stream requested for clientId={}", clientId);
        SseEmitter emitter = new SseEmitter(300_000L); // 5 min timeout

        Thread.ofVirtual().start(() -> orchestrator.run(clientId, new StepEmitter() {

            @Override
            public void step(String type, String message) {
                send(type, Map.of("message", message));
            }

            @Override
            public void delta(ChangeReport report) {
                send("delta", report);
            }

            @Override
            public void brief(Object briefJson) {
                send("brief", briefJson);
            }

            @Override
            public void error(String message) {
                send("error", Map.of("message", message));
                emitter.completeWithError(new RuntimeException(message));
            }

            @Override
            public void complete() {
                send("done", Map.of("message", "Brief generation complete."));
                emitter.complete();
            }

            private void send(String eventName, Object data) {
                try {
                    String json = MAPPER.writeValueAsString(data);
                    emitter.send(SseEmitter.event().name(eventName).data(json, MediaType.APPLICATION_JSON));
                    log.debug("[sse] event={} sent", eventName);
                } catch (Exception e) {
                    log.warn("[sse] failed to send event={}: {}", eventName, e.getMessage());
                    emitter.completeWithError(e);
                }
            }
        }));

        return emitter;
    }
}
