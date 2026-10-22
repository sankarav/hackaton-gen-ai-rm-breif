package com.demo.rmbrief.chat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BriefVersionRepository extends JpaRepository<BriefVersion, Long> {
    Optional<BriefVersion> findByBriefIdAndVersion(Long briefId, int version);

    List<BriefVersion> findByBriefIdOrderByVersionDesc(Long briefId);
}
