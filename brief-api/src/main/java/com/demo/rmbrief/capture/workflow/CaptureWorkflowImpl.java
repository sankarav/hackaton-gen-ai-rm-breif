package com.demo.rmbrief.capture.workflow;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.TemporalFailure;
import io.temporal.workflow.Workflow;

import java.time.Duration;

public class CaptureWorkflowImpl implements CaptureWorkflow {

    /** Speech-to-text on CPU can take minutes; give it room and back off between attempts. */
    private final CaptureActivities transcription = Workflow.newActivityStub(CaptureActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(20))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(10))
                            .setBackoffCoefficient(2)
                            .setMaximumAttempts(4)
                            .build())
                    .build());

    /** Claude calls and database work. */
    private final CaptureActivities steps = Workflow.newActivityStub(CaptureActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(5))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(3))
                            .setBackoffCoefficient(2)
                            .setMaximumInterval(Duration.ofMinutes(1))
                            .setMaximumAttempts(6)
                            .build())
                    .build());

    private String stage = "STARTED";
    private String decision;
    private String decidedBy;
    private String rejectReason;
    private boolean consentWithdrawn;
    private String withdrawnBy;

    @Override
    public void run(Input input) {
        long id = input.captureId();
        try {
            stage = "TRANSCRIBING";
            transcription.normalize(id);
            if (consentWithdrawn) { purge(id); return; }

            if (input.recording()) {
                stage = "LABELLING_SPEAKERS";
                steps.labelSpeakers(id);
            }
            stage = "EXTRACTING";
            steps.extract(id);
            stage = "RESOLVING";
            steps.resolve(id);
            if (consentWithdrawn) { purge(id); return; }

            steps.markReadyForReview(id);
            stage = "AWAITING_REVIEW";
            boolean decided = Workflow.await(Duration.ofSeconds(input.reviewReminderSeconds()),
                    () -> decision != null || consentWithdrawn);
            if (!decided) {
                steps.remindReviewer(id);
                Workflow.await(() -> decision != null || consentWithdrawn);
            }
            if (consentWithdrawn) { purge(id); return; }

            if ("APPROVE".equals(decision)) {
                stage = "COMMITTING";
                steps.commit(id, decidedBy);
            } else {
                steps.markRejected(id, decidedBy, rejectReason);
            }
        } catch (ActivityFailure e) {
            stage = "FAILED";
            steps.markFailed(id, rootMessage(e));
        }

        stage = "RETAINING_MEDIA";
        boolean withdrawn = Workflow.await(Duration.ofSeconds(input.retentionSeconds()), () -> consentWithdrawn);
        if (withdrawn) {
            purge(id);
        } else {
            steps.deleteRawMedia(id);
            stage = "DONE";
        }
    }

    private void purge(long id) {
        stage = "PURGING";
        steps.purge(id, withdrawnBy);
        stage = "PURGED";
    }

    private static String rootMessage(TemporalFailure e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        if (t instanceof ApplicationFailure af && af.getOriginalMessage() != null) return af.getOriginalMessage();
        return t.getMessage();
    }

    @Override
    public void approve(String actor) {
        if (decision == null) {
            decision = "APPROVE";
            decidedBy = actor;
        }
    }

    @Override
    public void reject(String actor, String reason) {
        if (decision == null) {
            decision = "REJECT";
            decidedBy = actor;
            rejectReason = reason;
        }
    }

    @Override
    public void withdrawConsent(String actor) {
        consentWithdrawn = true;
        withdrawnBy = actor;
    }

    @Override
    public String stage() {
        return stage;
    }
}
