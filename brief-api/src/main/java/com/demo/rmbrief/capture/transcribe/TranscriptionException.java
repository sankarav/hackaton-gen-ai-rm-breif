package com.demo.rmbrief.capture.transcribe;

public class TranscriptionException extends RuntimeException {

    private final boolean retryable;

    public TranscriptionException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
