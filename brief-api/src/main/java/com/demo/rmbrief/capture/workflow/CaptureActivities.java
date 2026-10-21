package com.demo.rmbrief.capture.workflow;

import io.temporal.activity.ActivityInterface;

/** Each activity takes only IDs and is idempotent, so Temporal can retry it safely. */
@ActivityInterface
public interface CaptureActivities {

    void normalize(long captureId);

    void labelSpeakers(long captureId);

    void extract(long captureId);

    void resolve(long captureId);

    void markReadyForReview(long captureId);

    void remindReviewer(long captureId);

    void commit(long captureId, String actor);

    void markRejected(long captureId, String actor, String reason);

    void deleteRawMedia(long captureId);

    void purge(long captureId, String actor);

    void markFailed(long captureId, String reason);
}
