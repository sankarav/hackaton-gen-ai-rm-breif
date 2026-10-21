package com.demo.rmbrief.capture;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** API shapes for the capture endpoints. Entities are never serialized directly. */
public final class CaptureDtos {

    private CaptureDtos() {}

    public record Summary(long id, String clientId, String clientName, LocalDate meetingDate, String audioMode,
                          String state, String stateDetail, Instant createdAt, Instant reviewReminderAt,
                          boolean decisionPending) {}

    public record Media(long id, String kind, String contentType, String originalName, boolean deleted) {}

    public record Consent(String method, Instant consentedAt, String recordedBy, Instant withdrawnAt) {}

    public record Segment(int seq, String source, Double startSec, Double endSec, String speaker,
                          boolean speakerInferred, boolean lowConfidence, String text) {}

    public record Item(long id, String category, String text, String owner, String datePhrase, LocalDate resolvedDate,
                       String productMention, String resolvedRef, String resolvedLabel, List<Integer> sources,
                       String confidence, boolean complianceFlag, List<String> flags, List<String> notes,
                       String decision, boolean edited) {}

    public record Audit(Instant at, String actor, String action, String detail) {}

    public record Detail(Summary capture, Long interactionId, String rejectReason, String decidedBy, Instant decidedAt,
                         String workflowId, List<Media> media, Consent consent, List<Segment> segments,
                         List<Item> items, List<Audit> audit) {}

    public record ItemEdit(String text, String owner, String resolvedDate, String decision) {}

    public record Reject(String reason) {}

    public record Policy(boolean recordingEnabled, Instant updatedAt, String updatedBy) {}

    public record PolicyUpdate(boolean recordingEnabled) {}

    public record Promise(long id, String citationId, String text, String owner, LocalDate dueDate, String status,
                          Instant createdAt, Long captureId) {}

    public record PromiseStatusUpdate(String status) {}

    public record Created(long id, String workflowId, String streamUrl) {}
}
