package com.qingfeng.music.lyrics.application;

import java.util.regex.Pattern;

public final class LyricsFormatting {
    private static final Pattern FENCE = Pattern.compile(
        "\\A(`{3,}|~{3,})(?:json|lyrics|text|plaintext)?\\h*\\n(.*)\\n\\1\\h*\\z", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern FENCE_LINE = Pattern.compile("(?m)^\\h*(?:`{3,}|~{3,})");

    private LyricsFormatting() {}

    /** Strip one enclosing fence only when the entire response is a single fenced block. */
    public static String unwrap(String value) {
        String text = value.replace("\r\n", "\n").replace('\r', '\n');
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        text = text.strip();
        var fence = FENCE.matcher(text);
        if (fence.matches() && !FENCE_LINE.matcher(fence.group(2)).find()) return fence.group(2).strip();
        return text;
    }

    public static boolean hasFence(String text) { return FENCE_LINE.matcher(text).find(); }
}
