package com.qingfeng.music.infrastructure.storage;

import com.qingfeng.music.common.exception.DomainException;
import java.awt.Color;
import java.awt.Font;
import java.awt.LinearGradientPaint;
import java.awt.RenderingHints;
import java.awt.font.LineBreakMeasurer;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.text.AttributedString;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Song titles are rendered into the stored JPEG, including downloaded and published covers. */
final class CoverTitleRenderer {
    private static final int MARGIN = 64;
    private static final int TEXT_WIDTH = 896;
    private static final int MAX_HEIGHT = 260;

    static void draw(BufferedImage image, String title) throws IOException {
        String text = title.replaceAll("[\\p{Cc}\\p{Cf}\\p{Z}]+", " ").strip();
        if (text.isBlank() || text.codePointCount(0, text.length()) > 100)
            throw new DomainException("INVALID_COVER_TITLE", "封面歌名需要 1 到 100 个字符");
        var graphics = image.createGraphics();
        try {
            Font titleFont;
            try (var stream = CoverTitleRenderer.class.getResourceAsStream("/fonts/NotoSansCJKsc-Bold.otf")) {
                if (stream == null) throw new IOException("Missing bundled cover font");
                titleFont = Font.createFont(Font.TRUETYPE_FONT, stream);
            } catch (java.awt.FontFormatException invalid) {
                throw new IOException("Invalid bundled cover font", invalid);
            }
            if (titleFont.canDisplayUpTo(text) != -1) titleFont = new Font(Font.SANS_SERIF, Font.BOLD, 72);
            if (titleFont.canDisplayUpTo(text) != -1)
                throw new DomainException("COVER_FONT_UNAVAILABLE", "封面字体缺少歌名所需字符，请配置支持该语言的字体");
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            List<TextLayout> lines = List.of();
            float height = 0;
            for (int size = 72; size >= 24; size -= 2) {
                var font = titleFont.deriveFont((float) size);
                var attributed = new AttributedString(text);
                attributed.addAttribute(TextAttribute.FONT, font);
                var measurer = new LineBreakMeasurer(attributed.getIterator(),
                        BreakIterator.getLineInstance(Locale.ROOT), graphics.getFontRenderContext());
                lines = new ArrayList<>();
                height = 0;
                while (measurer.getPosition() < text.length()) {
                    var line = measurer.nextLayout(TEXT_WIDTH);
                    lines.add(line);
                    height += line.getAscent() + line.getDescent() + 12;
                }
                if (height <= MAX_HEIGHT) break;
            }
            int top = 800 - (int) height;
            graphics.setPaint(new LinearGradientPaint(0, top - 100, 0, 920,
                    new float[]{0, 0.55f, 0.85f, 1},
                    new Color[]{new Color(0, 0, 0, 0), new Color(0, 0, 0, 170),
                            new Color(0, 0, 0, 140), new Color(0, 0, 0, 0)}));
            // Leave the lower edge, including the provider's AI mark, visible.
            graphics.fillRect(0, top - 100, image.getWidth(), 920 - top + 100);
            float baseline = top;
            for (var line : lines) {
                baseline += line.getAscent();
                graphics.setColor(new Color(0, 0, 0, 160));
                line.draw(graphics, MARGIN + 2, baseline + 3);
                graphics.setColor(Color.WHITE);
                line.draw(graphics, MARGIN, baseline);
                baseline += line.getDescent() + 12;
            }
        } finally {
            graphics.dispose();
        }
    }
}
