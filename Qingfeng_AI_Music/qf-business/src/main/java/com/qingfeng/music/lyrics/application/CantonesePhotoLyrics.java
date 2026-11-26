package com.qingfeng.music.lyrics.application;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.lyrics.api.LyricLanguage;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Photo lyrics requested in Cantonese must retain colloquial Cantonese through refinement. */
public final class CantonesePhotoLyrics {
    static final String LANGUAGE_MISMATCH = "LYRICS_LANGUAGE_MISMATCH";
    private static final Set<String> NAMES = Set.of("粤语", "粵語", "广东话", "廣東話", "香港粤语", "香港粵語",
        "粤语口语", "粵語口語", "香港粤语口语", "香港粵語口語", "cantonese", "hong kong cantonese");
    private static final Pattern ORAL_MARKERS = Pattern.compile(
        "喺|嘅|咗|唔|冇|嗰|佢|啲|咁|嘢|嚟|俾|畀|搵|睇|係|哋|而家|屋企|返去|鍾意|钟意|啱|未有|仲未|乜|邊度|边度|㗎|嘞|囉|啩");

    private CantonesePhotoLyrics() {}

    public static boolean requested(String language) {
        String name = LyricLanguage.customName(language);
        return name != null && NAMES.contains(name.toLowerCase(Locale.ROOT));
    }

    static void requireColloquialBody(String lyrics) {
        // Exclude section labels and repeated chorus lines from the coverage check.
        var lines = lyrics.lines().map(String::strip)
            .filter(line -> !line.isEmpty() && !line.startsWith("[")).distinct().toList();
        var markers = new HashSet<String>();
        int colloquialLines = 0;
        for (String line : lines) {
            var matcher = ORAL_MARKERS.matcher(line);
            if (matcher.find()) {
                colloquialLines++;
                do {
                    markers.add(matcher.group());
                } while (matcher.find());
            }
        }
        if (markers.size() < 3 || colloquialLines < Math.max(2, (lines.size() + 2) / 3)) {
            throw new DomainException(LANGUAGE_MISMATCH, "这次照片歌词未达到粤语口语要求，请重试。");
        }
    }
}
