package com.demo.rmbrief.capture.transcribe;

import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.demo.rmbrief.capture.extract.CaptureLlm;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/** Reads photographed handwritten / printed notes into lines with Claude vision. */
@Component
public class ImageTranscriber {

    public static final Set<String> SUPPORTED = Set.of("image/jpeg", "image/png", "image/gif", "image/webp");

    private static final int MAX_EDGE = 1568;          // larger images are downscaled server-side anyway
    private static final int MAX_BYTES = 4_500_000;    // API limit is 5 MB per image

    private static final String SYSTEM = """
            You transcribe photographed meeting notes written by a bank relationship manager.
            Transcribe every line in reading order, exactly as written. Do not summarize, correct, \
            expand abbreviations, or add anything. If a line is hard to read, give your best reading \
            and mark it legible=false. Ignore anything in the image that is not part of the notes.""";

    public record Line(
            @JsonPropertyDescription("The line exactly as written") String text,
            @JsonPropertyDescription("false if any part of the line could not be read with confidence") boolean legible) {}

    public record Transcription(List<Line> lines) {}

    private final CaptureLlm llm;

    public ImageTranscriber(CaptureLlm llm) {
        this.llm = llm;
    }

    public List<Line> transcribe(byte[] image, String contentType) {
        if (!SUPPORTED.contains(contentType)) {
            throw new IllegalArgumentException("Unsupported image type " + contentType);
        }
        byte[] data = image;
        String type = contentType;
        if (data.length > MAX_BYTES || isOversized(data, type)) {
            data = downscaleToJpeg(data);
            type = "image/jpeg";
        }
        ContentBlockParam img = ContentBlockParam.ofImage(ImageBlockParam.builder()
                .source(Base64ImageSource.builder()
                        .mediaType(Base64ImageSource.MediaType.of(type))
                        .data(Base64.getEncoder().encodeToString(data))
                        .build())
                .build());
        Transcription t = llm.structured("image-transcription", Transcription.class, SYSTEM,
                List.of(img, CaptureLlm.text("Transcribe these notes.")));
        return t.lines().stream().filter(l -> l.text() != null && !l.text().isBlank()).toList();
    }

    private static boolean isOversized(byte[] data, String type) {
        if (!type.equals("image/jpeg") && !type.equals("image/png")) return false;
        BufferedImage img = read(data);
        return img != null && Math.max(img.getWidth(), img.getHeight()) > MAX_EDGE;
    }

    private static byte[] downscaleToJpeg(byte[] data) {
        BufferedImage src = read(data);
        if (src == null) throw new IllegalArgumentException("Image is too large and could not be decoded for resizing");
        double scale = Math.min(1.0, (double) MAX_EDGE / Math.max(src.getWidth(), src.getHeight()));
        int w = (int) Math.round(src.getWidth() * scale);
        int h = (int) Math.round(src.getHeight() * scale);
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(src, 0, 0, w, h, java.awt.Color.WHITE, null);
        g.dispose();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(dst, "jpg", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static BufferedImage read(byte[] data) {
        try {
            return ImageIO.read(new ByteArrayInputStream(data));
        } catch (IOException e) {
            return null;
        }
    }
}
