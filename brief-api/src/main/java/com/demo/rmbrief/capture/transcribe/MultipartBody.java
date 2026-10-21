package com.demo.rmbrief.capture.transcribe;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Minimal multipart/form-data encoder for java.net.http. */
final class MultipartBody {

    private final String boundary = "----rmbrief" + UUID.randomUUID().toString().replace("-", "");
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    MultipartBody field(String name, String value) {
        write("--" + boundary + "\r\n");
        write("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        write(value + "\r\n");
        return this;
    }

    MultipartBody file(String name, String filename, String contentType, byte[] data) {
        write("--" + boundary + "\r\n");
        write("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename.replace("\"", "") + "\"\r\n");
        write("Content-Type: " + contentType + "\r\n\r\n");
        out.writeBytes(data);
        write("\r\n");
        return this;
    }

    byte[] build() {
        write("--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    String contentType() {
        return "multipart/form-data; boundary=" + boundary;
    }

    private void write(String s) {
        out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
    }
}
