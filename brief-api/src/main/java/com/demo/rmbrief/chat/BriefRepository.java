package com.demo.rmbrief.chat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BriefRepository extends JpaRepository<Brief, Long> {
    List<Brief> findTop20ByClientIdOrderByCreatedAtDesc(String clientId);
}
