package com.demo.rmbrief.capture;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditRepository extends JpaRepository<AuditEntry, Long> {
    List<AuditEntry> findByCaptureIdOrderByAt(Long captureId);
}
