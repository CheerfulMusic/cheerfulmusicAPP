package com.qingfeng.music.agent.application;

import java.util.regex.Pattern;

/** Structured lyric bodies are user data, never authorization for tools. */
public record AgentLyricInput(String instruction, String lyrics) {
    private static final Pattern SECTION = Pattern.compile("(?im)^\\s*(?:\\*\\*)?\\[(?:verse(?:\\s+\\d+)?|chorus(?:\\s+\\d+)?|pre-chorus|final chorus|bridge|intro|outro|interlude)\\](?:\\*\\*)?");
    public static AgentLyricInput parse(String text) {
        String value = text == null ? "" : text;
        var match = SECTION.matcher(value);
        if (!match.find()) return new AgentLyricInput(value, "");
        String instruction=value.substring(0, match.start()).strip().replaceFirst("(?m)^歌词(?:是|如下)?[：:]\\s*$", "").strip();
        return new AgentLyricInput(instruction, value.substring(match.start()).strip());
    }
}
