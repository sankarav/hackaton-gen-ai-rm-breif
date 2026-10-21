package com.demo.rmbrief.capture;

import com.demo.rmbrief.capture.media.MediaStore;
import com.demo.rmbrief.crm.ClientRepository;
import com.demo.rmbrief.crm.Interaction;
import com.demo.rmbrief.crm.InteractionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * State transitions and side effects of a capture after extraction. Every method is idempotent
 * because Temporal may retry an activity after a crash between doing the work and acknowledging it.
 */
@Service
public class CaptureLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(CaptureLifecycleService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CaptureRepository captures;
    private final CaptureMediaRepository media;
    private final TranscriptSegmentRepository segments;
    private final ExtractedItemRepository items;
    private final ClientPromiseRepository promises;
    private final ConsentRecordRepository consents;
    private final InteractionRepository interactions;
    private final ClientRepository clients;
    private final MediaStore store;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public CaptureLifecycleService(CaptureRepository captures, CaptureMediaRepository media,
                                   TranscriptSegmentRepository segments, ExtractedItemRepository items,
                                   ClientPromiseRepository promises, ConsentRecordRepository consents,
                                   InteractionRepository interactions, ClientRepository clients,
                                   MediaStore store, AuditService audit, TransactionTemplate tx) {
        this.captures = captures;
        this.media = media;
        this.segments = segments;
        this.items = items;
        this.promises = promises;
        this.consents = consents;
        this.interactions = interactions;
        this.clients = clients;
        this.store = store;
        this.audit = audit;
        this.tx = tx;
    }

    public void setState(long captureId, Capture.State state, String detail) {
        tx.executeWithoutResult(s -> {
            Capture c = captures.findById(captureId).orElseThrow();
            if (c.state == Capture.State.PURGED) return;
            c.state = state;
            c.stateDetail = detail;
            c.updatedAt = Instant.now();
        });
    }

    public void markReadyForReview(long captureId) {
        long pending = items.findByCaptureIdOrderById(captureId).stream()
                .filter(i -> i.decision == ExtractedItem.Decision.PENDING).count();
        setState(captureId, Capture.State.READY_FOR_REVIEW,
                pending == 0 ? "Ready for review" : pending + " item(s) need a decision");
        Capture c = captures.findById(captureId).orElseThrow();
        audit.record("system", "READY_FOR_REVIEW", c.clientId, captureId, "pending=" + pending);
    }

    public void remindReviewer(long captureId) {
        tx.executeWithoutResult(s -> {
            Capture c = captures.findById(captureId).orElseThrow();
            if (c.reviewReminderAt != null) return;
            c.reviewReminderAt = Instant.now();
            audit.record("system", "REVIEW_REMINDER", c.clientId, captureId, null);
        });
        log.info("[capture] {} review reminder raised", captureId);
    }

    public void commit(long captureId, String actor) {
        tx.executeWithoutResult(s -> {
            Capture c = captures.findById(captureId).orElseThrow();
            if (c.interactionId != null) return;

            List<ExtractedItem> accepted = items.findByCaptureIdOrderById(captureId).stream()
                    .filter(i -> i.decision == ExtractedItem.Decision.ACCEPTED).toList();
            List<ExtractedItem> acceptedPromises = accepted.stream()
                    .filter(i -> i.category == ExtractedItem.Category.PROMISE).toList();

            Interaction interaction = new Interaction();
            interaction.setClient(clients.getReferenceById(c.clientId));
            interaction.setMeetingDate(c.meetingDate);
            interaction.setNotes(composeNotes(accepted));
            interaction.setPromises(toJson(acceptedPromises.stream().map(CaptureLifecycleService::promiseLine).toList()));
            interaction = interactions.save(interaction);

            Instant now = Instant.now();
            for (ExtractedItem p : acceptedPromises) {
                ClientPromise row = new ClientPromise();
                row.clientId = c.clientId;
                row.interactionId = interaction.getId();
                row.captureId = captureId;
                row.text = p.text;
                row.owner = p.owner;
                row.dueDate = p.resolvedDate;
                row.resolvedRef = p.resolvedRef;
                row.createdAt = now;
                promises.save(row);
            }

            c.interactionId = interaction.getId();
            c.state = Capture.State.COMMITTED;
            c.stateDetail = "Saved to CRM";
            c.decidedAt = now;
            c.decidedBy = actor;
            c.updatedAt = now;
            audit.record(actor, "COMMITTED", c.clientId, captureId,
                    "interaction=" + interaction.getId() + " items=" + accepted.size() + " promises=" + acceptedPromises.size());
        });
    }

    public void markRejected(long captureId, String actor, String reason) {
        tx.executeWithoutResult(s -> {
            Capture c = captures.findById(captureId).orElseThrow();
            if (c.state == Capture.State.REJECTED) return;
            Instant now = Instant.now();
            c.state = Capture.State.REJECTED;
            c.stateDetail = "Rejected by reviewer";
            c.rejectReason = reason;
            c.decidedAt = now;
            c.decidedBy = actor;
            c.updatedAt = now;
            audit.record(actor, "REJECTED", c.clientId, captureId, null);
        });
    }

    /** Retention: removes the raw photos/audio; the reviewed transcript and items stay as the record. */
    public void deleteRawMedia(long captureId) {
        Capture c = captures.findById(captureId).orElseThrow();
        int deleted = deleteMediaFiles(captureId);
        if (deleted > 0) audit.record("system", "RAW_MEDIA_DELETED", c.clientId, captureId, "files=" + deleted);
    }

    /** Consent withdrawn: remove the recording and everything derived from it, including the CRM entry. */
    public void purge(long captureId, String actor) {
        int files = deleteMediaFiles(captureId);
        tx.executeWithoutResult(s -> {
            Capture c = captures.findById(captureId).orElseThrow();
            if (c.state == Capture.State.PURGED) return;
            segments.deleteByCaptureId(captureId);
            items.deleteByCaptureId(captureId);
            if (c.interactionId != null) {
                interactions.deleteById(c.interactionId);   // client_promise rows cascade with it
                c.interactionId = null;
            }
            consents.findByCaptureId(captureId).ifPresent(cr -> {
                if (cr.withdrawnAt == null) cr.withdrawnAt = Instant.now();
            });
            c.typedNotes = null;
            c.state = Capture.State.PURGED;
            c.stateDetail = "Consent withdrawn — recording and derived data deleted";
            c.updatedAt = Instant.now();
            audit.record(actor, "PURGED_CONSENT_WITHDRAWN", c.clientId, captureId, "files=" + files);
        });
    }

    public void markFailed(long captureId, String reason) {
        tx.executeWithoutResult(s -> {
            Capture c = captures.findById(captureId).orElseThrow();
            if (c.state == Capture.State.PURGED) return;
            c.state = Capture.State.FAILED;
            c.stateDetail = reason;
            c.updatedAt = Instant.now();
            audit.record("system", "FAILED", c.clientId, captureId, null);
        });
    }

    private int deleteMediaFiles(long captureId) {
        int count = 0;
        for (CaptureMedia m : media.findByCaptureIdOrderById(captureId)) {
            if (m.deletedAt != null) continue;
            store.delete(m.storageKey);
            m.deletedAt = Instant.now();
            media.save(m);
            count++;
        }
        return count;
    }

    static String composeNotes(List<ExtractedItem> accepted) {
        StringBuilder sb = new StringBuilder();
        accepted.stream().filter(i -> i.category == ExtractedItem.Category.SUMMARY).findFirst()
                .ifPresent(i -> sb.append(i.text));
        appendSection(sb, "Life events", accepted, ExtractedItem.Category.LIFE_EVENT);
        appendSection(sb, "Product interest", accepted, ExtractedItem.Category.PRODUCT_INTEREST);
        appendSection(sb, "Concerns", accepted, ExtractedItem.Category.CONCERN);
        appendSection(sb, "Next meeting", accepted, ExtractedItem.Category.NEXT_MEETING);
        return sb.toString().strip();
    }

    private static void appendSection(StringBuilder sb, String title, List<ExtractedItem> items, ExtractedItem.Category category) {
        List<String> lines = new ArrayList<>();
        for (ExtractedItem i : items) {
            if (i.category != category) continue;
            String line = i.text;
            if (i.resolvedDate != null) line += " (" + i.resolvedDate + ")";
            lines.add(line);
        }
        if (!lines.isEmpty()) {
            sb.append("\n").append(title).append(": ").append(lines.stream().collect(Collectors.joining("; ")));
        }
    }

    private static String promiseLine(ExtractedItem p) {
        String line = ("CLIENT".equals(p.owner) ? "Client: " : "") + p.text;
        return p.resolvedDate != null ? line + " (due " + p.resolvedDate + ")" : line;
    }

    private static String toJson(List<String> values) {
        try {
            return MAPPER.writeValueAsString(values);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
