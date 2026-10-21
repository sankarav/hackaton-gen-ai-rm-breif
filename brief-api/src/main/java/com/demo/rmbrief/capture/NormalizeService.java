package com.demo.rmbrief.capture;

import com.demo.rmbrief.capture.media.MediaStore;
import com.demo.rmbrief.capture.transcribe.ImageTranscriber;
import com.demo.rmbrief.capture.transcribe.TranscriptionProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns every input of a capture (typed notes, photos, audio) into numbered transcript segments.
 * Segments are the unit of source tracing: extracted items must cite them by seq.
 */
@Service
public class NormalizeService {

    private static final Logger log = LoggerFactory.getLogger(NormalizeService.class);
    private static final int LONG_LINE = 300;

    private final CaptureRepository captures;
    private final CaptureMediaRepository media;
    private final TranscriptSegmentRepository segments;
    private final MediaStore store;
    private final ImageTranscriber images;
    private final TranscriptionProvider speech;
    private final TransactionTemplate tx;

    public NormalizeService(CaptureRepository captures, CaptureMediaRepository media,
                            TranscriptSegmentRepository segments, MediaStore store,
                            ImageTranscriber images, TranscriptionProvider speech, TransactionTemplate tx) {
        this.captures = captures;
        this.media = media;
        this.segments = segments;
        this.store = store;
        this.images = images;
        this.speech = speech;
        this.tx = tx;
    }

    public void normalize(long captureId) {
        Capture capture = captures.findById(captureId).orElseThrow();
        List<TranscriptSegment> out = new ArrayList<>();

        if (capture.typedNotes != null) {
            for (String line : splitNotes(capture.typedNotes)) {
                out.add(TranscriptSegment.of(captureId, out.size() + 1, "TEXT", "RM", line));
            }
        }

        for (CaptureMedia m : media.findByCaptureIdOrderById(captureId)) {
            if (m.deletedAt != null) continue;
            byte[] bytes = store.get(m.storageKey);
            if (m.kind == CaptureMedia.Kind.IMAGE) {
                for (ImageTranscriber.Line line : images.transcribe(bytes, m.contentType)) {
                    TranscriptSegment s = TranscriptSegment.of(captureId, out.size() + 1, "IMAGE:" + m.id, "RM", line.text().trim());
                    s.lowConfidence = !line.legible();
                    out.add(s);
                }
            } else {
                String name = m.originalName != null ? m.originalName : "audio-" + m.id;
                TranscriptionProvider.Transcript t = speech.transcribe(bytes, m.contentType, name);
                // A voice memo is the RM speaking; a recording needs speaker labelling afterwards.
                String speaker = capture.isRecording() ? "UNKNOWN" : "RM";
                for (TranscriptionProvider.Segment seg : t.segments()) {
                    TranscriptSegment s = TranscriptSegment.of(captureId, out.size() + 1, "AUDIO:" + m.id, speaker, seg.text());
                    s.startSec = seg.startSec();
                    s.endSec = seg.endSec();
                    out.add(s);
                }
                log.info("[capture] {} transcribed media={} via {} → {} segments", captureId, m.id, speech.name(), t.segments().size());
            }
        }

        if (out.isEmpty()) {
            throw new IllegalArgumentException("Nothing could be read from this capture");
        }

        tx.executeWithoutResult(status -> {
            segments.deleteByCaptureId(captureId);
            segments.flush();
            segments.saveAll(out);
        });
        log.info("[capture] {} normalized into {} segments", captureId, out.size());
    }

    static List<String> splitNotes(String notes) {
        List<String> lines = new ArrayList<>();
        for (String raw : notes.split("\\R")) {
            String line = raw.strip().replaceFirst("^[-*•]\\s*", "");
            if (line.isEmpty()) continue;
            if (line.length() <= LONG_LINE) {
                lines.add(line);
            } else {
                for (String sentence : line.split("(?<=[.!?])\\s+")) {
                    if (!sentence.isBlank()) lines.add(sentence.strip());
                }
            }
        }
        return lines;
    }
}
