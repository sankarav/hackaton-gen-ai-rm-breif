package com.demo.rmbrief.capture;

import org.springframework.stereotype.Service;

import java.time.Instant;

/** Append-only audit trail. Callers pass IDs and actions only, never note or transcript content. */
@Service
public class AuditService {

    private final AuditRepository repo;

    public AuditService(AuditRepository repo) {
        this.repo = repo;
    }

    public void record(String actor, String action, String clientId, Long captureId, String detail) {
        AuditEntry e = new AuditEntry();
        e.at = Instant.now();
        e.actor = actor;
        e.action = action;
        e.clientId = clientId;
        e.captureId = captureId;
        e.detail = detail;
        repo.save(e);
    }
}
