package com.qingfeng.music.server.web.app;

import com.qingfeng.music.common.exception.DomainException;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.springframework.web.multipart.MultipartFile;

final class PhotoLyricsImage {
    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final int MAX_EDGE = 1600;

    private PhotoLyricsImage() {}

    static byte[] jpeg(MultipartFile file) {
        if (file.isEmpty() || file.getSize() > MAX_BYTES) throw invalid();
        try (var source = file.getInputStream(); var input = new MemoryCacheImageInputStream(source)) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid();
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String type = reader.getFormatName();
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if ((!type.equalsIgnoreCase("JPEG") && !type.equalsIgnoreCase("PNG"))
                    || width < 16 || height < 16 || width > 8000 || height > 8000 || (long) width * height > 16_000_000)
                    throw invalid();
                var params = reader.getDefaultReadParam();
                int sample = Math.max(1, Math.max(width, height) / MAX_EDGE);
                params.setSourceSubsampling(sample, sample, 0, 0);
                var image = reader.read(0, params);
                try {
                    return encode(image);
                } finally {
                    image.flush();
                }
            } finally {
                reader.dispose();
            }
        } catch (IOException | IllegalArgumentException invalidImage) {
            throw invalid();
        }
    }

    private static byte[] encode(BufferedImage image) throws IOException {
        double scale = Math.min(1.0, (double) MAX_EDGE / Math.max(image.getWidth(), image.getHeight()));
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        var rgb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = rgb.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(image, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        var bytes = new ByteArrayOutputStream();
        try (var output = new MemoryCacheImageOutputStream(bytes)) {
            if (!ImageIO.write(rgb, "jpeg", output)) throw invalid();
            output.flush();
        } finally {
            rgb.flush();
        }
        return bytes.toByteArray();
    }

    private static DomainException invalid() {
        return new DomainException("INVALID_LYRICS_REQUEST", "请上传有效的 JPG 或 PNG 图片，最大 5 MB、1600 万像素。");
    }
}
