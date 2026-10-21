package com.demo.rmbrief.capture;

import jakarta.persistence.*;

import java.time.Instant;

/** Compliance-owned switch for meeting recording. Off unless explicitly enabled. */
@Entity
@Table(name = "recording_policy")
public class RecordingPolicy {

    public static final String GLOBAL = "GLOBAL";

    @Id
    public String scope;

    @Column(name = "recording_enabled", nullable = false)
    public boolean recordingEnabled;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    @Column(name = "updated_by", nullable = false)
    public String updatedBy;
}
