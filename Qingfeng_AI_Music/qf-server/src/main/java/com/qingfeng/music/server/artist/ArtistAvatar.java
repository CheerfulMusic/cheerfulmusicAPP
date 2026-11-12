package com.qingfeng.music.server.artist;

import com.qingfeng.music.common.exception.DomainException;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.web.multipart.MultipartFile;

final class ArtistAvatar {
    private static final int OUTPUT_SIZE = 192;
    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final int MAX_SIDE = 8192;
    private static final long MAX_PIXELS = 20_000_000L;

    private ArtistAvatar() {}

    static byte[] jpeg(MultipartFile file) {
        if (file.isEmpty() || file.getSize() > MAX_BYTES) {
            throw new DomainException("INVALID_ARTIST_AVATAR", "请选择 5 MB 以内的 JPG 或 PNG 图片。");
        }
        try (var stream = file.getInputStream(); var input = new MemoryCacheImageInputStream(stream)) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalidImage();
            var reader = readers.next();
            try {
                String format = reader.getFormatName();
                if (!"JPEG".equalsIgnoreCase(format) && !"PNG".equalsIgnoreCase(format)) throw invalidImage();
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > MAX_SIDE || height > MAX_SIDE || (long) width * height > MAX_PIXELS) {
                    throw new DomainException("INVALID_ARTIST_AVATAR", "头像尺寸过大，请缩小图片后重试。");
                }
                int side = Math.min(width, height);
                int left = (width - side) / 2;
                int top = (height - side) / 2;
                var settings = reader.getDefaultReadParam();
                settings.setSourceRegion(new java.awt.Rectangle(left, top, side, side));
                int sample = Math.max(1, side / (OUTPUT_SIZE * 2));
                settings.setSourceSubsampling(sample, sample, 0, 0);
                var source = reader.read(0, settings);
                var output = new BufferedImage(OUTPUT_SIZE, OUTPUT_SIZE, BufferedImage.TYPE_INT_RGB);
                var graphics = output.createGraphics();
                try {
                    graphics.setColor(Color.WHITE);
                    graphics.fillRect(0, 0, OUTPUT_SIZE, OUTPUT_SIZE);
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    graphics.drawImage(source, 0, 0, OUTPUT_SIZE, OUTPUT_SIZE, null);
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
            throw invalidImage();
        }
    }

    private static DomainException invalidImage() {
        return new DomainException("INVALID_ARTIST_AVATAR", "无法读取头像，请选择有效的 JPG 或 PNG 图片。");
    }
}
