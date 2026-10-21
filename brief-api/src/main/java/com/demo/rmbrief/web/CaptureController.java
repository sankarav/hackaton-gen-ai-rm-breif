package com.demo.rmbrief.web;

import com.demo.rmbrief.capture.Capture;
import com.demo.rmbrief.capture.CaptureDtos;
import com.demo.rmbrief.capture.CaptureRepository;
import com.demo.rmbrief.capture.CaptureService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
public class CaptureController {

    private static final Logger log = LoggerFactory.getLogger(CaptureController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CaptureService captures;
    private final CaptureRepository captureRepo;

    public CaptureController(CaptureService captures, CaptureRepository captureRepo) {
        this.captures = captures;
        this.captureRepo = captureRepo;
    }

    @PostMapping(value = "/captures", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CaptureDtos.Created> create(
            @RequestParam String clientId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate meetingDate,
            @RequestParam(required = false) String notes,
            @RequestParam(required = false) Capture.AudioMode audioMode,
            @RequestParam(defaultValue = "false") boolean consentGiven,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant consentAt,
            @RequestParam(required = false) List<MultipartFile> images,
            @RequestParam(required = false) MultipartFile audio) {
        var req = new CaptureService.NewCapture(clientId, meetingDate, notes, audioMode, consentGiven, consentAt);
        return ResponseEntity.accepted().body(captures.create(req, images, audio));
    }

    @GetMapping("/captures")
    public List<CaptureDtos.Summary> list(@RequestParam(required = false) String clientId) {
        return captures.list(clientId);
    }

    @GetMapping("/captures/{id}")
    public CaptureDtos.Detail detail(@PathVariable long id) {
        return captures.detail(id);
    }

    @GetMapping("/captures/{id}/media/{mediaId}")
    public ResponseEntity<byte[]> media(@PathVariable long id, @PathVariable long mediaId) {
        CaptureService.MediaContent m = captures.media(id, mediaId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(m.contentType()))
                .body(m.bytes());
    }

    @PutMapping("/captures/{id}/items/{itemId}")
    public CaptureDtos.Item updateItem(@PathVariable long id, @PathVariable long itemId, @RequestBody CaptureDtos.ItemEdit edit) {
        return captures.updateItem(id, itemId, edit);
    }

    @PostMapping("/captures/{id}/approve")
    public ResponseEntity<Map<String, String>> approve(@PathVariable long id) {
        captures.approve(id);
        return ResponseEntity.accepted().body(Map.of("message", "Approved — saving to CRM"));
    }

    @PostMapping("/captures/{id}/reject")
    public ResponseEntity<Map<String, String>> reject(@PathVariable long id, @RequestBody(required = false) CaptureDtos.Reject body) {
        captures.reject(id, body == null ? null : body.reason());
        return ResponseEntity.accepted().body(Map.of("message", "Rejected"));
    }

    @PostMapping("/captures/{id}/withdraw-consent")
    public ResponseEntity<Map<String, String>> withdrawConsent(@PathVariable long id) {
        captures.withdrawConsent(id);
        return ResponseEntity.accepted().body(Map.of("message", "Consent withdrawn — deleting recording and derived data"));
    }

    /**
     * Streams state changes until the capture needs the RM (ready for review) or reaches a final state.
     * Polls Postgres, so it survives app restarts mid-workflow.
     */
    @GetMapping(value = "/captures/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable long id) {
        captures.find(id);
        SseEmitter emitter = new SseEmitter(30 * 60_000L);
        Thread.ofVirtual().start(() -> {
            String last = null;
            try {
                while (true) {
                    Capture c = captureRepo.findById(id).orElseThrow();
                    String key = c.state + "|" + c.stateDetail;
                    if (!Objects.equals(key, last)) {
                        last = key;
                        String json = MAPPER.writeValueAsString(Map.of("state", c.state.name(),
                                "detail", c.stateDetail == null ? "" : c.stateDetail));
                        emitter.send(SseEmitter.event().name("state").data(json, MediaType.APPLICATION_JSON));
                    }
                    boolean waitingOnRm = c.state == Capture.State.READY_FOR_REVIEW && c.decidedAt == null;
                    if (c.state.isFinal() || waitingOnRm) break;
                    Thread.sleep(1000);
                }
                emitter.complete();
            } catch (Exception e) {
                log.debug("[capture] stream {} closed: {}", id, e.getMessage());
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }
}
