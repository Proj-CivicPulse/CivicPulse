package com.civicpulse.backend_spring.service.photo;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Iterator;
import java.util.Optional;

/**
 * Turns an uploaded image into the two files that are actually stored: a JPEG
 * of at most {@value #MAX_EDGE} px on its long edge, and a
 * {@value #THUMB_EDGE} px thumbnail.
 *
 * <p>Re-encoding is the EXIF decision (contract §7): the pixels are decoded
 * and written fresh, so GPS coordinates, the device id and every other
 * metadata block are gone. The report already carries its location on
 * purpose; a second, more precise copy in the file adds risk and no function.
 * Decoding is also the real type check — bytes that do not decode as JPEG,
 * PNG or WebP are refused whatever the upload claimed to be.
 */
public final class ImageNormaliser {

    public static final int MIN_EDGE = 64;
    public static final int MAX_EDGE = 2048;
    public static final int THUMB_EDGE = 320;

    private ImageNormaliser() {
    }

    public record Normalised(byte[] photo, byte[] thumbnail, int width, int height) {
    }

    /**
     * Decoded pixels allowed. A small, highly compressed file can declare an
     * enormous canvas; checked from the header BEFORE decoding, so an 8 MB
     * upload cannot ask for gigabytes of heap.
     */
    static final long MAX_PIXELS = 50_000_000L;

    /** Empty when the bytes are not a decodable image, or one too small to be a report. */
    public static Optional<Normalised> normalise(byte[] bytes) {
        BufferedImage source;
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
            if (readers == null || !readers.hasNext()) return Optional.empty();
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                long w = reader.getWidth(0);
                long h = reader.getHeight(0);
                if (w < MIN_EDGE || h < MIN_EDGE || w * h > MAX_PIXELS) return Optional.empty();
                source = reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
        if (source == null) {
            return Optional.empty();
        }
        BufferedImage photo = scaled(source, MAX_EDGE);
        BufferedImage thumb = scaled(source, THUMB_EDGE);
        return Optional.of(new Normalised(jpeg(photo, 0.85f), jpeg(thumb, 0.8f), photo.getWidth(), photo.getHeight()));
    }

    /** Scaled to fit {@code edge} (never up), flattened onto white: JPEG has no alpha. */
    private static BufferedImage scaled(BufferedImage source, int edge) {
        double factor = Math.min(1.0, (double) edge / Math.max(source.getWidth(), source.getHeight()));
        int w = Math.max(1, (int) Math.round(source.getWidth() * factor));
        int h = Math.max(1, (int) Math.round(source.getHeight() * factor));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.drawImage(source, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static byte[] jpeg(BufferedImage image, float quality) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.write(null, new IIOImage(image, null, null), param);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
