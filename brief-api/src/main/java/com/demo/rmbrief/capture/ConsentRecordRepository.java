package com.demo.rmbrief.capture;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ConsentRecordRepository extends JpaRepository<ConsentRecord, Long> {
    Optional<ConsentRecord> findByCaptureId(Long captureId);
}
