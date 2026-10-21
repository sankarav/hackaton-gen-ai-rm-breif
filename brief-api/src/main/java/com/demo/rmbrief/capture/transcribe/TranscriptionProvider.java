package com.demo.rmbrief.capture.transcribe;

import java.util.List;

/**
 * Speech-to-text behind one interface so the backend is a config switch
 * ({@code app.transcription.provider}: local-whisper | openai).
 */
public interface TranscriptionProvider {

    String name();

    Transcript transcribe(byte[] audio, String contentType, String filename);

    /** @param speaker null when the provider does not separate speakers */
    record Segment(double startSec, double endSec, String text, String speaker) {}

    record Transcript(List<Segment> segments) {}
}
