package com.qingfeng.music.agent.application;

import java.util.regex.Pattern;

/** Structured lyric bodies are user data, never authorization for tools. */
public record AgentLyricInput(String instruction, String lyrics) {
    private static final String LABEL="(?:verse|chorus|pre-chorus|final chorus|bridge|intro|outro|interlude|主歌|副歌|预副歌|桥段|前奏|间奏|尾奏|尾声|结尾副歌)(?:\\h*[0-9一二三四五六七八九十]+)?";
    private static final Pattern SECTION=Pattern.compile("(?im)^\\h*(?:\\*\\*)?(?:\\["+LABEL+"\\]|【"+LABEL+"】)(?:\\*\\*)?");
    private static final Pattern ENVELOPE=Pattern.compile("(?im)^\\h*(?:歌词(?:是|如下)?|lyrics)\\h*[：:]\\h*(?:\\R|$)");
    public static AgentLyricInput parse(String text) {
        String value = text == null ? "" : text;
        var match = SECTION.matcher(value);
        var envelope=ENVELOPE.matcher(value);
        int sectionStart=match.find()?match.start():value.length();
        if(envelope.find()&&envelope.start()<=sectionStart&&!value.substring(envelope.end()).isBlank())
            return new AgentLyricInput(value.substring(0,envelope.start()).strip(),value.substring(envelope.end()).strip());
        if(sectionStart==value.length())return new AgentLyricInput(value,"");
        return new AgentLyricInput(value.substring(0,sectionStart).strip(),value.substring(sectionStart).strip());
    }
}
