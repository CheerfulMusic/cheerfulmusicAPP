package com.qingfeng.music.agent.api;

import java.util.List;

/**
 * 工具枚举与参数契约，W1 冻结。W2 只做「参数适配 → 调用现有 Service → 投影 SongCard」，
 * 不在此处写业务逻辑。参数字段以后端现有能力可表达的最小集合为准。
 */
public enum AgentTool {
    GENERATE_SONG("generate_song"),
    GENERATE_LYRICS("generate_lyrics"),
    MODIFY_SECTION("modify_section"),
    EXTEND_SONG("extend_song"),
    SET_STYLE("set_style"),
    GET_CURRENT_SONG("get_current_song");

    private final String id;

    AgentTool(String id) { this.id = id; }

    public String id() { return id; }

    public static AgentTool fromId(String id) {
        if (id == null) return null;
        for (AgentTool tool : values()) if (tool.id.equals(id)) return tool;
        return null;
    }

    public record GenerateSongArgs(String text, String style, String language, Integer bpm,
                                   String vocal, List<String> instruments, String mode, String revisionId) {
        public GenerateSongArgs(String text, String style, String language, Integer bpm, String vocal, List<String> instruments) {
            this(text, style, language, bpm, vocal, instruments, null, null);
        }
    }

    public record GenerateLyricsArgs(String text, String language, String mode, String revisionId) {
        public GenerateLyricsArgs(String text, String language, String mode) { this(text, language, mode, null); }
    }

    public record ModifySectionArgs(String section, String replacementLyrics, Double startS, Double endS) {}

    public record ExtendSongArgs(String direction, Integer seconds) {}

    public record SetStyleArgs(String style, String mood, Integer bpm, String language,
                               List<String> instruments, String vocal) {
        public SetStyleArgs(String style,String mood,Integer bpm,String language,List<String> instruments) {
            this(style,mood,bpm,language,instruments,null);
        }
    }

    public record GetCurrentSongArgs() {}
}
