package com.demo.rmbrief.capture;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;

/** One post-meeting capture: typed notes, photos and/or audio for a single client meeting. */
@Entity
@Table(name = "capture")
public class Capture {

    public enum State {
        RECEIVED, TRANSCRIBING, LABELLING_SPEAKERS, EXTRACTING, RESOLVING,
        READY_FOR_REVIEW, COMMITTED, REJECTED, FAILED, PURGED;

        public boolean isFinal() {
            return this == COMMITTED || this == REJECTED || this == FAILED || this == PURGED;
        }
    }

    public enum AudioMode { VOICE_MEMO, RECORDING }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "client_id", nullable = false)
    public String clientId;

    @Column(name = "meeting_date", nullable = false)
    public LocalDate meetingDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "audio_mode")
    public AudioMode audioMode;

    @Column(name = "typed_notes", columnDefinition = "text")
    public String typedNotes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public State state;

    @Column(name = "state_detail", columnDefinition = "text")
    public String stateDetail;

    @Column(name = "workflow_id")
    public String workflowId;

    @Column(name = "interaction_id")
    public Long interactionId;

    @Column(name = "created_by", nullable = false)
    public String createdBy;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    @Column(name = "review_reminder_at")
    public Instant reviewReminderAt;

    @Column(name = "decided_at")
    public Instant decidedAt;

    @Column(name = "decided_by")
    public String decidedBy;

    @Column(name = "reject_reason", columnDefinition = "text")
    public String rejectReason;

    public boolean isRecording() {
        return audioMode == AudioMode.RECORDING;
    }
}
