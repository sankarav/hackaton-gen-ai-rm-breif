package com.demo.rmbrief.capture;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ExtractedItemRepository extends JpaRepository<ExtractedItem, Long> {
    List<ExtractedItem> findByCaptureIdOrderById(Long captureId);

    Optional<ExtractedItem> findByIdAndCaptureId(Long id, Long captureId);

    @Modifying
    @Query("delete from ExtractedItem i where i.captureId = :captureId")
    void deleteByCaptureId(Long captureId);
}
