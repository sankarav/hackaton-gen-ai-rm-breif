package com.demo.rmbrief.capture.workflow;

import com.anthropic.errors.BadRequestException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.errors.UnprocessableEntityException;
import com.demo.rmbrief.capture.Capture;
import com.demo.rmbrief.capture.CaptureLifecycleService;
import com.demo.rmbrief.capture.NormalizeService;
import com.demo.rmbrief.capture.extract.CaptureLlm;
import com.demo.rmbrief.capture.extract.ExtractionService;
import com.demo.rmbrief.capture.extract.SpeakerLabeler;
import com.demo.rmbrief.capture.resolve.ResolveService;
import com.demo.rmbrief.capture.transcribe.TranscriptionException;
import io.temporal.failure.ApplicationFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Temporal activities backed by Spring services. Failures that a retry cannot fix (bad input,
 * a model refusal, auth errors) are raised as non-retryable so the capture fails fast.
 */
@Component
public class CaptureActivitiesImpl implements CaptureActivities {

    private static final Logger log = LoggerFactory.getLogger(CaptureActivitiesImpl.class);

    private final NormalizeService normalizer;
    private final SpeakerLabeler speakers;
    private final ExtractionService extraction;
    private final ResolveService resolver;
    private final CaptureLifecycleService lifecycle;

    public CaptureActivitiesImpl(NormalizeService normalizer, SpeakerLabeler speakers, ExtractionService extraction,
                                 ResolveService resolver, CaptureLifecycleService lifecycle) {
        this.normalizer = normalizer;
        this.speakers = speakers;
        this.extraction = extraction;
        this.resolver = resolver;
        this.lifecycle = lifecycle;
    }

    @Override
    public void normalize(long captureId) {
        step(captureId, Capture.State.TRANSCRIBING, "Reading notes and transcribing audio", () -> normalizer.normalize(captureId));
    }

    @Override
    public void labelSpeakers(long captureId) {
        step(captureId, Capture.State.LABELLING_SPEAKERS, "Separating RM and client speech", () -> speakers.label(captureId));
    }

    @Override
    public void extract(long captureId) {
        step(captureId, Capture.State.EXTRACTING, "Extracting summary, promises and signals", () -> extraction.extract(captureId));
    }

    @Override
    public void resolve(long captureId) {
        step(captureId, Capture.State.RESOLVING, "Resolving dates and matching accounts", () -> resolver.resolve(captureId));
    }

    @Override
    public void markReadyForReview(long captureId) {
        lifecycle.markReadyForReview(captureId);
    }

    @Override
    public void remindReviewer(long captureId) {
        lifecycle.remindReviewer(captureId);
    }

    @Override
    public void commit(long captureId, String actor) {
        lifecycle.commit(captureId, actor);
    }

    @Override
    public void markRejected(long captureId, String actor, String reason) {
        lifecycle.markRejected(captureId, actor, reason);
    }

    @Override
    public void deleteRawMedia(long captureId) {
        lifecycle.deleteRawMedia(captureId);
    }

    @Override
    public void purge(long captureId, String actor) {
        lifecycle.purge(captureId, actor);
    }

    @Override
    public void markFailed(long captureId, String reason) {
        lifecycle.markFailed(captureId, reason);
    }

    private void step(long captureId, Capture.State state, String detail, Runnable work) {
        lifecycle.setState(captureId, state, detail);
        try {
            work.run();
        } catch (IllegalArgumentException | CaptureLlm.LlmRefusalException | BadRequestException
                 | UnprocessableEntityException | UnauthorizedException | PermissionDeniedException e) {
            log.warn("[capture] {} {} failed permanently: {}", captureId, state, e.getMessage());
            throw ApplicationFailure.newNonRetryableFailure(e.getMessage(), e.getClass().getSimpleName());
        } catch (TranscriptionException e) {
            if (e.retryable()) throw e;
            throw ApplicationFailure.newNonRetryableFailure(e.getMessage(), "TranscriptionException");
        }
    }
}
