package com.demo.rmbrief.capture.workflow;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * One workflow per capture: transcribe → (label speakers) → extract → resolve → wait for the RM's
 * decision → commit or reject → hold raw media for the retention period → delete it.
 * Only the capture ID crosses into Temporal; notes, transcripts and media stay in Postgres / the media store.
 */
@WorkflowInterface
public interface CaptureWorkflow {

    String TASK_QUEUE = "capture";

    static String workflowId(long captureId) {
        return "capture-" + captureId;
    }

    record Input(long captureId, boolean recording, long reviewReminderSeconds, long retentionSeconds) {}

    @WorkflowMethod
    void run(Input input);

    @SignalMethod
    void approve(String actor);

    @SignalMethod
    void reject(String actor, String reason);

    @SignalMethod
    void withdrawConsent(String actor);

    @QueryMethod
    String stage();
}
