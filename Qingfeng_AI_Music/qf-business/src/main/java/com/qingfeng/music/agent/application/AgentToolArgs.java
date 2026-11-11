package com.qingfeng.music.agent.application;

import com.qingfeng.music.agent.api.AgentTool;
import com.qingfeng.music.agent.model.AgentJson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 6 个工具参数 record 与扁平 JSON 的互转。集中在本类，避免在每个处理器里重复解析。
 * 列表（instruments）以 \u0001 拼接存储；数值按字符串存储；反序列化时按字段类型还原。
 */
public final class AgentToolArgs {

    private static final String SEP = "\u0001";

    private AgentToolArgs() {}

    public static String serialize(AgentTool tool, Object args) {
        return AgentJson.write(toMap(tool, args));
    }

    public static Object deserialize(AgentTool tool, String json) {
        return fromMap(tool, AgentJson.read(json));
    }

    private static Map<String, Object> toMap(AgentTool tool, Object args) {
        Map<String, Object> m = new LinkedHashMap<>();
        switch (tool) {
            case GENERATE_SONG -> {
                var a = (AgentTool.GenerateSongArgs) args;
                put(m, "text", a.text());
                put(m, "style", a.style());
                put(m, "language", a.language());
                put(m, "bpm", a.bpm());
                put(m, "vocal", a.vocal());
                put(m, "instruments", join(a.instruments()));
            }
            case GENERATE_LYRICS -> {
                var a = (AgentTool.GenerateLyricsArgs) args;
                put(m, "text", a.text());
                put(m, "language", a.language());
                put(m, "mode", a.mode());
            }
            case MODIFY_SECTION -> {
                var a = (AgentTool.ModifySectionArgs) args;
                put(m, "section", a.section());
                put(m, "replacementLyrics", a.replacementLyrics());
                put(m, "startS", a.startS());
                put(m, "endS", a.endS());
            }
            case EXTEND_SONG -> {
                var a = (AgentTool.ExtendSongArgs) args;
                put(m, "direction", a.direction());
                put(m, "seconds", a.seconds());
            }
            case SET_STYLE -> {
                var a = (AgentTool.SetStyleArgs) args;
                put(m, "style", a.style());
                put(m, "mood", a.mood());
                put(m, "bpm", a.bpm());
                put(m, "language", a.language());
                put(m, "instruments", join(a.instruments()));
            }
            case GET_CURRENT_SONG -> { /* 无参数 */ }
        }
        return m;
    }

    private static Object fromMap(AgentTool tool, Map<String, Object> m) {
        return switch (tool) {
            case GENERATE_SONG -> new AgentTool.GenerateSongArgs(
                    str(m, "text"), str(m, "style"), str(m, "language"),
                    integer(m, "bpm"), str(m, "vocal"), split(str(m, "instruments")));
            case GENERATE_LYRICS -> new AgentTool.GenerateLyricsArgs(
                    str(m, "text"), str(m, "language"), str(m, "mode"));
            case MODIFY_SECTION -> new AgentTool.ModifySectionArgs(
                    str(m, "section"), str(m, "replacementLyrics"),
                    decimal(m, "startS"), decimal(m, "endS"));
            case EXTEND_SONG -> new AgentTool.ExtendSongArgs(
                    str(m, "direction"), integer(m, "seconds"));
            case SET_STYLE -> new AgentTool.SetStyleArgs(
                    str(m, "style"), str(m, "mood"), integer(m, "bpm"),
                    str(m, "language"), split(str(m, "instruments")));
            case GET_CURRENT_SONG -> new AgentTool.GetCurrentSongArgs();
        };
    }

    private static void put(Map<String, Object> m, String key, Object value) {
        m.put(key, value == null ? null : value.toString());
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    private static Integer integer(Map<String, Object> m, String key) {
        String v = str(m, key);
        return v == null || v.isBlank() ? null : Integer.valueOf(v.trim());
    }

    private static Double decimal(Map<String, Object> m, String key) {
        String v = str(m, key);
        return v == null || v.isBlank() ? null : Double.valueOf(v.trim());
    }

    private static String join(List<String> values) {
        if (values == null || values.isEmpty()) return null;
        return String.join(SEP, values);
    }

    private static List<String> split(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<String> list = new ArrayList<>();
        for (String part : value.split(java.util.regex.Pattern.quote(SEP))) {
            if (!part.isBlank()) list.add(part);
        }
        return List.copyOf(list);
    }
}
