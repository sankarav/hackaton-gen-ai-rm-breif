package com.demo.rmbrief.capture;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CaptureRepository extends JpaRepository<Capture, Long> {
    List<Capture> findTop50ByOrderByCreatedAtDesc();
    List<Capture> findTop50ByClientIdOrderByCreatedAtDesc(String clientId);
}
