package com.demo.rmbrief.capture;

import jakarta.persistence.*;

/** One line of normalized source text. Extracted items cite segments by {@link #seq}. */
@Entity
@Table(name = "transcript_segment")
public class TranscriptSegment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "capture_id", nullable = false)
    public Long captureId;

    @Column(nullable = false)
    public int seq;

    @Column(nullable = false)
    public String source;

    @Column(name = "start_sec")
    public Double startSec;

    @Column(name = "end_sec")
    public Double endSec;

    @Column(nullable = false)
    public String speaker;

    @Column(name = "speaker_inferred", nullable = false)
    public boolean speakerInferred;

    @Column(name = "low_confidence", nullable = false)
    public boolean lowConfidence;

    @Column(nullable = false, columnDefinition = "text")
    public String text;

    public static TranscriptSegment of(long captureId, int seq, String source, String speaker, String text) {
        TranscriptSegment s = new TranscriptSegment();
        s.captureId = captureId;
        s.seq = seq;
        s.source = source;
        s.speaker = speaker;
        s.text = text;
        return s;
    }
}
