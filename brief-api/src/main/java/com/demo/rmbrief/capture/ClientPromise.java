package com.demo.rmbrief.capture;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;

/** A follow-up commitment approved from a capture; DeltaService tracks it until closed. */
@Entity
@Table(name = "client_promise")
public class ClientPromise {

    public enum Status { OPEN, DONE, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "client_id", nullable = false)
    public String clientId;

    @Column(name = "interaction_id")
    public Long interactionId;

    @Column(name = "capture_id")
    public Long captureId;

    @Column(nullable = false, columnDefinition = "text")
    public String text;

    @Column(nullable = false)
    public String owner;

    @Column(name = "due_date")
    public LocalDate dueDate;

    @Column(name = "resolved_ref")
    public String resolvedRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public Status status = Status.OPEN;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "closed_at")
    public Instant closedAt;

    public String citationId() {
        return "promise-" + id;
    }
}
