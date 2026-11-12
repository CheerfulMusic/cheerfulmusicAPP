package com.qingfeng.music.server.artist;

import com.qingfeng.music.common.exception.DomainException;
import java.awt.Color;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.web.multipart.MultipartFile;

final class ArtistImage {
    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final int MAX_SIDE = 8192;
    private static final long MAX_PIXELS = 20_000_000L;

    enum Kind {
        AVATAR("avatar", "头像", "INVALID_ARTIST_AVATAR", 192, 192),
        COVER("cover", "封面", "INVALID_ARTIST_COVER", 1600, 900);

        final String column;
        private final String label;
        private final String errorCode;
        private final int maxWidth;
        private final int maxHeight;

        Kind(String column, String label, String errorCode, int maxWidth, int maxHeight) {
            this.column = column;
            this.label = label;
            this.errorCode = errorCode;
            this.maxWidth = maxWidth;
            this.maxHeight = maxHeight;
        }

        DomainException invalid(String message) {
            return new DomainException(errorCode, message);
        }

        private DomainException unreadable() {
            return invalid("无法读取" + label + "，请选择有效的 JPG 或 PNG 图片。");
        }
    }

    private ArtistImage() {}

    static byte[] jpeg(MultipartFile file, Kind kind) {
        if (file.isEmpty() || file.getSize() > MAX_BYTES) {
            throw kind.invalid("请选择 5 MB 以内的 JPG 或 PNG 图片。");
        }
        try (var stream = file.getInputStream(); var input = new MemoryCacheImageInputStream(stream)) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw kind.unreadable();
            var reader = readers.next();
            try {
                String format = reader.getFormatName();
                if (!"JPEG".equalsIgnoreCase(format) && !"PNG".equalsIgnoreCase(format)) throw kind.unreadable();
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > MAX_SIDE || height > MAX_SIDE || (long) width * height > MAX_PIXELS) {
                    throw kind.invalid(kind.label + "尺寸过大，请缩小图片后重试。");
                }
                var region = new Rectangle(width, height);
                int targetWidth = kind.maxWidth;
                int targetHeight = kind.maxHeight;
                if (kind == Kind.AVATAR) {
                    int side = Math.min(width, height);
                    region.setBounds((width - side) / 2, (height - side) / 2, side, side);
                } else {
                    // Retain the cover's aspect ratio so the browser can crop it consistently with the draft.
                    double scale = Math.min(1, Math.min((double) targetWidth / width, (double) targetHeight / height));
                    targetWidth = Math.max(1, (int) Math.round(width * scale));
                    targetHeight = Math.max(1, (int) Math.round(height * scale));
                }
                var settings = reader.getDefaultReadParam();
                settings.setSourceRegion(region);
                int sample = Math.max(1, Math.min(region.width / targetWidth, region.height / targetHeight) / 2);
                settings.setSourceSubsampling(sample, sample, 0, 0);
                var source = reader.read(0, settings);
                var output = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
                var graphics = output.createGraphics();
                try {
                    graphics.setColor(Color.WHITE);
                    graphics.fillRect(0, 0, targetWidth, targetHeight);
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
                } finally {
                    graphics.dispose();
                }
                var bytes = new ByteArrayOutputStream();
                if (!ImageIO.write(output, "jpeg", bytes)) throw new IllegalStateException("JPEG writer unavailable");
                return bytes.toByteArray();
            } finally {
                reader.dispose();
            }
        } catch (IOException | IllegalArgumentException error) {
            throw kind.unreadable();
        }
    }
}
