package com.demo.rmbrief.capture;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "capture_media")
public class CaptureMedia {

    public enum Kind { IMAGE, AUDIO }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "capture_id", nullable = false)
    public Long captureId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public Kind kind;

    @Column(name = "content_type", nullable = false)
    public String contentType;

    @Column(name = "original_name")
    public String originalName;

    @Column(name = "storage_key", nullable = false)
    public String storageKey;

    @Column(name = "size_bytes", nullable = false)
    public long sizeBytes;

    @Column(name = "deleted_at")
    public Instant deletedAt;
}
