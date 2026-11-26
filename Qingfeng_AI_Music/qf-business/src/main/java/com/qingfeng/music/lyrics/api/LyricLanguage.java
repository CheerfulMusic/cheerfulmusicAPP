package com.qingfeng.music.lyrics.api;

import java.util.regex.Pattern;

public final class LyricLanguage {
    public static final int MAX_LENGTH = 55;
    private static final String CUSTOM_PREFIX = "custom:";
    public static final String REGEX = "(?:zh-CN|en|mixed|ja|ko|es|"
        + CUSTOM_PREFIX + "\\p{L}[\\p{L}\\p{M} \\p{Pd}'’()]{0,47})";
    private static final Pattern VALID = Pattern.compile(REGEX);

    private LyricLanguage() {}

    public static boolean isSupported(String value) {
        return value != null && value.length() <= MAX_LENGTH && VALID.matcher(value).matches();
    }

    public static boolean usesLocalRhymeDictionary(String value) {
        return "zh-CN".equals(value) || "en".equals(value) || "mixed".equals(value);
    }

    public static String customName(String value) {
        return value != null && value.startsWith(CUSTOM_PREFIX) && isSupported(value)
            ? value.substring(CUSTOM_PREFIX.length()).strip() : null;
    }
}
