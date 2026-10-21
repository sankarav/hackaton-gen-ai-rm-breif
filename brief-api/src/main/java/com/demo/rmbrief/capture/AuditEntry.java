package com.demo.rmbrief.capture;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "audit_log")
public class AuditEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false)
    public Instant at;

    @Column(nullable = false)
    public String actor;

    @Column(nullable = false)
    public String action;

    @Column(name = "client_id")
    public String clientId;

    @Column(name = "capture_id")
    public Long captureId;

    public String detail;
}
