package com.qingfeng.music.infrastructure.storage;

import com.qingfeng.music.common.exception.DomainException;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CoverTitleRendererTest {
    @TempDir Path directory;

    @Test void titledJpegContainsReadableTextAndPreservesTheBottomMark() throws Exception {
        byte[] picture = image();
        var store = new CoverImageStore(directory, null, "", "covers/", false);
        var untitled = store.save(new ByteArrayInputStream(picture));
        var titled = store.saveTitled(new ByteArrayInputStream(picture), "偏偏很快乐");
        try (var plainStream = store.open(untitled.key()); var titleStream = store.open(titled.key())) {
            var plain = ImageIO.read(plainStream);
            var result = ImageIO.read(titleStream);
            assertEquals(1024, result.getWidth());
            assertEquals(1024, result.getHeight());
            int visibleText = 0;
            for (int y = 500; y < 830; y++)
                for (int x = 50; x < 975; x++)
                    if ((result.getRGB(x, y) & 255) > 220 && (plain.getRGB(x, y) & 255) < 10) visibleText++;
            assertTrue(visibleText > 100, "Song title must be part of the encoded image");
            assertEquals(plain.getRGB(100, 970), result.getRGB(100, 970));
        }
    }

    @Test void aLongMixedTitleFitsAndBlankTitlesAreRejected() throws Exception {
        var store = new CoverImageStore(directory, null, "", "covers/", false);
        String title = "很长的歌名与 English Love ".repeat(5).strip();
        assertTrue(title.codePointCount(0, title.length()) <= 100);
        var saved = store.saveTitled(new ByteArrayInputStream(image()), title);
        assertTrue(saved.size() > 0);
        assertThrows(DomainException.class, () -> store.saveTitled(new ByteArrayInputStream(image()), "  "));
        assertThrows(DomainException.class, () -> store.saveTitled(new ByteArrayInputStream(image()), "长".repeat(101)));
    }

    private byte[] image() throws Exception {
        var canvas = new BufferedImage(1024, 1024, BufferedImage.TYPE_INT_RGB);
        var paint = canvas.createGraphics();
        paint.setColor(Color.RED);
        paint.fillRect(0, 940, 1024, 84);
        paint.dispose();
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(canvas, "png", bytes);
        return bytes.toByteArray();
    }
}
