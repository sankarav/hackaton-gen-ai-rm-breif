package com.demo.rmbrief.capture;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface TranscriptSegmentRepository extends JpaRepository<TranscriptSegment, Long> {
    List<TranscriptSegment> findByCaptureIdOrderBySeq(Long captureId);

    @Modifying
    @Query("delete from TranscriptSegment s where s.captureId = :captureId")
    void deleteByCaptureId(Long captureId);
}
