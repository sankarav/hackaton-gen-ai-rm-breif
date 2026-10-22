package com.demo.rmbrief.chat;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "brief")
public class Brief {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "client_id", nullable = false)
    public String clientId;

    @Column(name = "change_report", nullable = false, columnDefinition = "text")
    public String changeReport;

    @Column(name = "current_version", nullable = false)
    public int currentVersion;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
}
