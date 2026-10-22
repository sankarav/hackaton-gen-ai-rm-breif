package com.demo.rmbrief.web;

import com.demo.rmbrief.chat.BriefEdit;
import com.demo.rmbrief.chat.BriefStore;
import com.demo.rmbrief.chat.ChatService;
import com.demo.rmbrief.chat.ChatTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Saved briefs, their versions, and chat about them. Chat turns stream as SSE over a POST. */
@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private final BriefStore store;
    private final ChatService chat;

    public ChatController(BriefStore store, ChatService chat) {
        this.store = store;
        this.chat = chat;
    }

    public record ChatRequest(List<ChatService.ChatMessage> history, String message) {}

    public record ApplyRequest(int baseVersion, BriefEdit edit) {}

    @GetMapping("/briefs/saved")
    public List<BriefStore.Summary> recent(@RequestParam String clientId) {
        return store.recent(clientId);
    }

    @GetMapping("/briefs/saved/{briefId}")
    public BriefStore.View brief(@PathVariable long briefId, @RequestParam(required = false) Integer version) {
        return store.view(briefId, version);
    }

    @PostMapping("/briefs/saved/{briefId}/edits")
    public BriefStore.View apply(@PathVariable long briefId, @RequestBody ApplyRequest req) {
        return store.apply(briefId, req.baseVersion(), req.edit());
    }

    @PostMapping(value = "/briefs/saved/{briefId}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@PathVariable long briefId, @RequestBody ChatRequest req) {
        store.view(briefId, null);
        chat.validate(req.history(), req.message());

        SseEmitter emitter = new SseEmitter(5 * 60_000L);
        AtomicBoolean closed = new AtomicBoolean(false);
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> closed.set(true));
        emitter.onError(e -> closed.set(true));

        Thread.ofVirtual().start(() -> {
            ChatService.Events events = new ChatService.Events() {
                @Override public void step(String message) { send("step", Map.of("message", message)); }
                @Override public void delta(String text) { send("delta", Map.of("text", text)); }
                @Override public void proposal(Map<String, Object> p) { send("proposal", p); }
                @Override public void answer(String text, Map<String, ChatTools.Citation> citations) {
                    send("answer", Map.of("text", text, "citations", citations));
                }
                @Override public boolean cancelled() { return closed.get(); }

                private void send(String name, Object data) {
                    if (closed.get()) return;
                    try {
                        emitter.send(SseEmitter.event().name(name).data(MAPPER.writeValueAsString(data), MediaType.APPLICATION_JSON));
                    } catch (Exception e) {
                        closed.set(true);
                    }
                }
            };
            try {
                chat.turn(briefId, req.history(), req.message(), events);
                if (!closed.get()) emitter.complete();
            } catch (Exception e) {
                log.error("[chat] brief={} turn failed", briefId, e);
                if (!closed.get()) {
                    try {
                        emitter.send(SseEmitter.event().name("error")
                                .data(MAPPER.writeValueAsString(Map.of("message", "Chat failed: " + e.getMessage())), MediaType.APPLICATION_JSON));
                        emitter.complete();
                    } catch (Exception ignored) {
                        emitter.completeWithError(e);
                    }
                }
            }
        });
        return emitter;
    }
}
