package com.demo.rmbrief.capture;

import com.demo.rmbrief.capture.media.MediaStore;
import com.demo.rmbrief.capture.transcribe.ImageTranscriber;
import com.demo.rmbrief.capture.workflow.CaptureWorkflow;
import com.demo.rmbrief.crm.Client;
import com.demo.rmbrief.crm.ClientRepository;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * API-side operations: accept uploads, start the workflow, record review edits and decisions.
 * Decisions are delivered to the running workflow as Temporal signals.
 */
@Service
public class CaptureService {

    private static final Logger log = LoggerFactory.getLogger(CaptureService.class);

    private static final int MAX_IMAGES = 6;
    private static final long MAX_IMAGE_BYTES = 20L * 1024 * 1024;
    private static final long MAX_AUDIO_BYTES = 100L * 1024 * 1024;
    private static final int MAX_NOTES_CHARS = 20_000;
    private static final Set<String> OWNERS = Set.of("RM", "CLIENT", "UNKNOWN");

    private final CaptureRepository captures;
    private final CaptureMediaRepository media;
    private final ConsentRecordRepository consents;
    private final TranscriptSegmentRepository segments;
    private final ExtractedItemRepository items;
    private final ClientPromiseRepository promises;
    private final RecordingPolicyRepository policies;
    private final AuditRepository auditLog;
    private final ClientRepository clients;
    private final MediaStore store;
    private final AuditService audit;
    private final CaptureLifecycleService lifecycle;
    private final WorkflowClient temporal;
    private final TransactionTemplate tx;
    private final Duration reviewReminder;
    private final Duration memoRetention;
    private final Duration recordingRetention;

    public CaptureService(CaptureRepository captures, CaptureMediaRepository media, ConsentRecordRepository consents,
                          TranscriptSegmentRepository segments, ExtractedItemRepository items,
                          ClientPromiseRepository promises, RecordingPolicyRepository policies,
                          AuditRepository auditLog, ClientRepository clients, MediaStore store, AuditService audit,
                          CaptureLifecycleService lifecycle, WorkflowClient temporal, TransactionTemplate tx,
                          @Value("${app.capture.review-reminder}") Duration reviewReminder,
                          @Value("${app.capture.retention.notes}") Duration memoRetention,
                          @Value("${app.capture.retention.recording}") Duration recordingRetention) {
        this.captures = captures;
        this.media = media;
        this.consents = consents;
        this.segments = segments;
        this.items = items;
        this.promises = promises;
        this.policies = policies;
        this.auditLog = auditLog;
        this.clients = clients;
        this.store = store;
        this.audit = audit;
        this.lifecycle = lifecycle;
        this.temporal = temporal;
        this.tx = tx;
        this.reviewReminder = reviewReminder;
        this.memoRetention = memoRetention;
        this.recordingRetention = recordingRetention;
    }

    // ── create ────────────────────────────────────────────────────────────────

    public record NewCapture(String clientId, LocalDate meetingDate, String notes, Capture.AudioMode audioMode,
                             boolean consentGiven, Instant consentAt) {}

    public CaptureDtos.Created create(NewCapture req, List<MultipartFile> images, MultipartFile audio) {
        Client client = clients.findById(req.clientId())
                .orElseThrow(() -> bad("Unknown client " + req.clientId()));
        String notes = req.notes() == null || req.notes().isBlank() ? null : req.notes().strip();
        List<MultipartFile> imageFiles = images == null ? List.of() : images.stream().filter(f -> !f.isEmpty()).toList();
        boolean hasAudio = audio != null && !audio.isEmpty();

        if (notes == null && imageFiles.isEmpty() && !hasAudio) throw bad("Add notes, a photo or audio");
        if (notes != null && notes.length() > MAX_NOTES_CHARS) throw bad("Notes are longer than " + MAX_NOTES_CHARS + " characters");
        if (req.meetingDate() == null) throw bad("meetingDate is required");
        if (req.meetingDate().isAfter(LocalDate.now())) throw bad("Meeting date can't be in the future");
        if (imageFiles.size() > MAX_IMAGES) throw bad("At most " + MAX_IMAGES + " photos per capture");
        for (MultipartFile f : imageFiles) {
            if (!ImageTranscriber.SUPPORTED.contains(baseType(f))) throw bad("Photos must be JPEG, PNG, GIF or WebP");
            if (f.getSize() > MAX_IMAGE_BYTES) throw bad("Each photo must be under 20 MB");
        }
        if (hasAudio) {
            if (req.audioMode() == null) throw bad("audioMode is required with audio");
            if (!baseType(audio).startsWith("audio/") && !baseType(audio).equals("video/webm")) throw bad("Unsupported audio type");
            if (audio.getSize() > MAX_AUDIO_BYTES) throw bad("Audio must be under 100 MB");
        }
        boolean recording = hasAudio && req.audioMode() == Capture.AudioMode.RECORDING;
        if (recording) {
            if (!recordingEnabled()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Meeting recording is disabled by compliance policy");
            if (!req.consentGiven() || req.consentAt() == null) throw bad("Client consent must be captured before recording");
        }

        String actor = actorFor(client);
        List<String> written = new ArrayList<>();
        long id;
        try {
            id = tx.execute(status -> {
                Instant now = Instant.now();
                Capture c = new Capture();
                c.clientId = client.getClientId();
                c.meetingDate = req.meetingDate();
                c.typedNotes = notes;
                c.audioMode = hasAudio ? req.audioMode() : null;
                c.state = Capture.State.RECEIVED;
                c.stateDetail = "Queued";
                c.createdBy = actor;
                c.createdAt = now;
                c.updatedAt = now;
                c = captures.save(c);
                c.workflowId = CaptureWorkflow.workflowId(c.id);

                for (MultipartFile f : imageFiles) saveMedia(c.id, CaptureMedia.Kind.IMAGE, f, written);
                if (hasAudio) saveMedia(c.id, CaptureMedia.Kind.AUDIO, audio, written);

                if (recording) {
                    ConsentRecord cr = new ConsentRecord();
                    cr.captureId = c.id;
                    cr.method = "CHECKBOX_AND_VERBAL";
                    cr.consentedAt = req.consentAt();
                    cr.recordedBy = actor;
                    consents.save(cr);
                }
                audit.record(actor, "CAPTURE_CREATED", c.clientId, c.id,
                        "notes=" + (notes != null) + " images=" + imageFiles.size() + " audio=" + (hasAudio ? req.audioMode() : "none"));
                return c.id;
            });
        } catch (RuntimeException e) {
            written.forEach(store::delete);
            throw e;
        }

        try {
            Duration retention = recording ? recordingRetention : memoRetention;
            CaptureWorkflow wf = temporal.newWorkflowStub(CaptureWorkflow.class, WorkflowOptions.newBuilder()
                    .setTaskQueue(CaptureWorkflow.TASK_QUEUE)
                    .setWorkflowId(CaptureWorkflow.workflowId(id))
                    .build());
            WorkflowClient.start(wf::run, new CaptureWorkflow.Input(id, recording,
                    reviewReminder.toSeconds(), retention.toSeconds()));
        } catch (RuntimeException e) {
            log.error("[capture] {} workflow start failed: {}", id, e.getMessage());
            lifecycle.markFailed(id, "Workflow engine unavailable — try again shortly");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Workflow engine unavailable", e);
        }
        return new CaptureDtos.Created(id, CaptureWorkflow.workflowId(id), "/captures/" + id + "/stream");
    }

    private void saveMedia(long captureId, CaptureMedia.Kind kind, MultipartFile f, List<String> written) {
        try {
            String key = store.put(f.getBytes());
            written.add(key);
            CaptureMedia m = new CaptureMedia();
            m.captureId = captureId;
            m.kind = kind;
            m.contentType = baseType(f);
            m.originalName = f.getOriginalFilename();
            m.storageKey = key;
            m.sizeBytes = f.getSize();
            media.save(m);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ── read ──────────────────────────────────────────────────────────────────

    public List<CaptureDtos.Summary> list(String clientId) {
        Map<String, String> names = clients.findAll().stream().collect(Collectors.toMap(Client::getClientId, Client::getFullName));
        List<Capture> rows = clientId == null || clientId.isBlank()
                ? captures.findTop50ByOrderByCreatedAtDesc()
                : captures.findTop50ByClientIdOrderByCreatedAtDesc(clientId);
        return rows.stream().map(c -> summary(c, names.get(c.clientId))).toList();
    }

    public CaptureDtos.Detail detail(long id) {
        Capture c = find(id);
        String name = clients.findById(c.clientId).map(Client::getFullName).orElse(c.clientId);
        return new CaptureDtos.Detail(
                summary(c, name), c.interactionId, c.rejectReason, c.decidedBy, c.decidedAt, c.workflowId,
                media.findByCaptureIdOrderById(id).stream()
                        .map(m -> new CaptureDtos.Media(m.id, m.kind.name(), m.contentType, m.originalName, m.deletedAt != null))
                        .toList(),
                consents.findByCaptureId(id)
                        .map(cr -> new CaptureDtos.Consent(cr.method, cr.consentedAt, cr.recordedBy, cr.withdrawnAt))
                        .orElse(null),
                segments.findByCaptureIdOrderBySeq(id).stream()
                        .map(s -> new CaptureDtos.Segment(s.seq, s.source, s.startSec, s.endSec, s.speaker,
                                s.speakerInferred, s.lowConfidence, s.text))
                        .toList(),
                items.findByCaptureIdOrderById(id).stream().map(CaptureService::item).toList(),
                auditLog.findByCaptureIdOrderByAt(id).stream()
                        .map(a -> new CaptureDtos.Audit(a.at, a.actor, a.action, a.detail))
                        .toList());
    }

    public Capture find(long id) {
        return captures.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Capture not found"));
    }

    public record MediaContent(byte[] bytes, String contentType) {}

    public MediaContent media(long captureId, long mediaId) {
        CaptureMedia m = media.findById(mediaId)
                .filter(x -> x.captureId == captureId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Media not found"));
        if (m.deletedAt != null) throw new ResponseStatusException(HttpStatus.GONE, "Deleted under the retention policy");
        return new MediaContent(store.get(m.storageKey), m.contentType);
    }

    // ── review ────────────────────────────────────────────────────────────────

    public CaptureDtos.Item updateItem(long captureId, long itemId, CaptureDtos.ItemEdit edit) {
        return tx.execute(status -> {
            Capture c = find(captureId);
            requireReviewable(c);
            ExtractedItem i = items.findByIdAndCaptureId(itemId, captureId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found"));
            if (edit.text() != null && !edit.text().strip().equals(i.text)) {
                if (edit.text().isBlank()) throw bad("Text can't be empty");
                i.text = edit.text().strip();
                i.edited = true;
            }
            if (edit.owner() != null) {
                if (!OWNERS.contains(edit.owner())) throw bad("owner must be RM, CLIENT or UNKNOWN");
                i.owner = edit.owner();
            }
            if (edit.resolvedDate() != null) {
                i.resolvedDate = edit.resolvedDate().isBlank() ? null : LocalDate.parse(edit.resolvedDate());
            }
            if (edit.decision() != null) {
                ExtractedItem.Decision d = ExtractedItem.Decision.valueOf(edit.decision().toUpperCase(Locale.ROOT));
                if (d == ExtractedItem.Decision.PENDING) throw bad("decision must be ACCEPTED or REJECTED");
                if (i.category == ExtractedItem.Category.SUMMARY && d == ExtractedItem.Decision.REJECTED) {
                    throw bad("The summary can be edited but not removed");
                }
                i.decision = d;
            }
            return item(items.save(i));
        });
    }

    public void approve(long captureId) {
        Capture c = find(captureId);
        String actor = actorFor(c);
        tx.executeWithoutResult(status -> {
            Capture fresh = find(captureId);
            requireReviewable(fresh);
            List<ExtractedItem> all = items.findByCaptureIdOrderById(captureId);
            long pending = all.stream().filter(i -> i.decision == ExtractedItem.Decision.PENDING).count();
            if (pending > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, pending + " flagged item(s) still need a decision");
            fresh.decidedAt = Instant.now();
            fresh.decidedBy = actor;
            fresh.stateDetail = "Approved — saving to CRM";
            audit.record(actor, "APPROVE_REQUESTED", fresh.clientId, captureId, null);
        });
        signalOrUndo(captureId, () -> stub(captureId).approve(actor));
    }

    public void reject(long captureId, String reason) {
        Capture c = find(captureId);
        String actor = actorFor(c);
        tx.executeWithoutResult(status -> {
            Capture fresh = find(captureId);
            requireReviewable(fresh);
            fresh.decidedAt = Instant.now();
            fresh.decidedBy = actor;
            audit.record(actor, "REJECT_REQUESTED", fresh.clientId, captureId, null);
        });
        signalOrUndo(captureId, () -> stub(captureId).reject(actor, reason == null ? "" : reason.strip()));
    }

    /** If the signal can't be delivered, reopen the capture for review instead of leaving it stuck. */
    private void signalOrUndo(long captureId, Runnable signal) {
        try {
            signal.run();
        } catch (RuntimeException e) {
            tx.executeWithoutResult(status -> {
                Capture c = find(captureId);
                c.decidedAt = null;
                c.decidedBy = null;
                c.stateDetail = "Ready for review";
            });
            log.error("[capture] {} signal failed: {}", captureId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Workflow engine unavailable — try again", e);
        }
    }

    public void withdrawConsent(long captureId) {
        Capture c = find(captureId);
        String actor = actorFor(c);
        ConsentRecord consent = consents.findByCaptureId(captureId)
                .orElseThrow(() -> bad("Only consented recordings can have consent withdrawn"));
        if (c.state == Capture.State.PURGED) throw new ResponseStatusException(HttpStatus.CONFLICT, "Already purged");
        consent.withdrawnAt = Instant.now();
        consents.save(consent);
        audit.record(actor, "CONSENT_WITHDRAWN", c.clientId, captureId, null);
        try {
            stub(captureId).withdrawConsent(actor);
        } catch (WorkflowNotFoundException e) {
            // Workflow already finished (past retention) — purge directly.
            lifecycle.purge(captureId, actor);
        }
    }

    private void requireReviewable(Capture c) {
        if (c.state != Capture.State.READY_FOR_REVIEW) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Capture is " + c.state + ", not ready for review");
        }
        if (c.decidedAt != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "A decision was already made");
    }

    private CaptureWorkflow stub(long captureId) {
        return temporal.newWorkflowStub(CaptureWorkflow.class, CaptureWorkflow.workflowId(captureId));
    }

    // ── policy & promises ─────────────────────────────────────────────────────

    public boolean recordingEnabled() {
        return policies.findById(RecordingPolicy.GLOBAL).map(p -> p.recordingEnabled).orElse(false);
    }

    public CaptureDtos.Policy policy() {
        RecordingPolicy p = policies.findById(RecordingPolicy.GLOBAL).orElseThrow();
        return new CaptureDtos.Policy(p.recordingEnabled, p.updatedAt, p.updatedBy);
    }

    public CaptureDtos.Policy setPolicy(boolean enabled, String actor) {
        tx.executeWithoutResult(status -> {
            RecordingPolicy p = policies.findById(RecordingPolicy.GLOBAL).orElseThrow();
            p.recordingEnabled = enabled;
            p.updatedAt = Instant.now();
            p.updatedBy = actor;
            audit.record(actor, enabled ? "RECORDING_ENABLED" : "RECORDING_DISABLED", null, null, "scope=GLOBAL");
        });
        return policy();
    }

    public List<CaptureDtos.Promise> openPromises(String clientId) {
        return promises.findByClientIdAndStatusOrderByDueDateAsc(clientId, ClientPromise.Status.OPEN).stream()
                .map(p -> new CaptureDtos.Promise(p.id, p.citationId(), p.text, p.owner, p.dueDate, p.status.name(), p.createdAt, p.captureId))
                .toList();
    }

    public void setPromiseStatus(long promiseId, String status) {
        ClientPromise.Status s = ClientPromise.Status.valueOf(status.toUpperCase(Locale.ROOT));
        if (s == ClientPromise.Status.OPEN) throw bad("status must be DONE or CANCELLED");
        tx.executeWithoutResult(t -> {
            ClientPromise p = promises.findById(promiseId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Promise not found"));
            p.status = s;
            p.closedAt = Instant.now();
            String actor = clients.findById(p.clientId).map(CaptureService::actorFor).orElse("rm");
            audit.record(actor, "PROMISE_" + s, p.clientId, p.captureId, "promise=" + p.id);
        });
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    // No authentication exists yet: the actor is the client's assigned RM. Replace with the signed-in user.
    private static String actorFor(Client client) {
        return "rm:" + client.getRmName();
    }

    private String actorFor(Capture c) {
        return clients.findById(c.clientId).map(CaptureService::actorFor).orElse("rm");
    }

    private CaptureDtos.Summary summary(Capture c, String clientName) {
        return new CaptureDtos.Summary(c.id, c.clientId, clientName, c.meetingDate,
                c.audioMode == null ? null : c.audioMode.name(), c.state.name(), c.stateDetail, c.createdAt,
                c.reviewReminderAt, c.decidedAt != null);
    }

    private static CaptureDtos.Item item(ExtractedItem i) {
        return new CaptureDtos.Item(i.id, i.category.name(), i.text, i.owner, i.datePhrase, i.resolvedDate,
                i.productMention, i.resolvedRef, i.resolvedLabel, i.sourceSeqList(), i.confidence,
                i.complianceFlag, i.flagList(), i.noteList(), i.decision.name(), i.edited);
    }

    private static String baseType(MultipartFile f) {
        String ct = f.getContentType() == null ? "" : f.getContentType();
        int semi = ct.indexOf(';');
        return (semi >= 0 ? ct.substring(0, semi) : ct).trim().toLowerCase(Locale.ROOT);
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
