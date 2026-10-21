package com.demo.rmbrief.capture;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CaptureMediaRepository extends JpaRepository<CaptureMedia, Long> {
    List<CaptureMedia> findByCaptureIdOrderById(Long captureId);
}
