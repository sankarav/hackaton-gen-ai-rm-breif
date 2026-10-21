package com.demo.rmbrief.capture;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "consent_record")
public class ConsentRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "capture_id", nullable = false, unique = true)
    public Long captureId;

    @Column(nullable = false)
    public String method;

    @Column(name = "consented_at", nullable = false)
    public Instant consentedAt;

    @Column(name = "recorded_by", nullable = false)
    public String recordedBy;

    @Column(name = "withdrawn_at")
    public Instant withdrawnAt;
}
