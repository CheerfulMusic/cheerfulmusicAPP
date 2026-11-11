package com.qingfeng.music.agent.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 无第三方依赖的极简 JSON：仅支持「对象 + 字符串值」。
 * W2 的对话意图 / 歌曲卡片序列化专用——数值与列表一律按字符串存储，避免引入 JSON 库。
 * 不追求通用性，只为可控的小结构服务。
 */
public final class AgentJson {

    private AgentJson() {}

    public static String write(Map<String, Object> object) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : object.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(jsonString(entry.getKey())).append(':');
            Object value = entry.getValue();
            if (value == null) sb.append("null");
            else if (value instanceof Map) sb.append(write((Map<String, Object>) value));
            else sb.append(jsonString(value.toString()));
        }
        return sb.append('}').toString();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> read(String json) {
        int[] index = {skipWhitespace(json, 0)};
        if (index[0] >= json.length() || json.charAt(index[0]) != '{') return new LinkedHashMap<>();
        return (Map<String, Object>) readValue(json, index);
    }

    private static Object readValue(String source, int[] index) {
        if (index[0] >= source.length()) return null;
        char c = source.charAt(index[0]);
        if (c == '{') return readObject(source, index);
        if (c == '"') return readString(source, index);
        if (source.startsWith("null", index[0])) {
            index[0] += 4;
            return null;
        }
        int start = index[0];
        while (index[0] < source.length() && source.charAt(index[0]) != ','
                && source.charAt(index[0]) != '}') {
            index[0]++;
        }
        return source.substring(start, index[0]).trim();
    }

    private static Map<String, Object> readObject(String source, int[] index) {
        Map<String, Object> map = new LinkedHashMap<>();
        index[0]++; // 越过 {
        while (index[0] < source.length()) {
            index[0] = skipWhitespace(source, index[0]);
            if (index[0] < source.length() && source.charAt(index[0]) == '}') {
                index[0]++;
                break;
            }
            String key = readString(source, index);
            index[0] = skipWhitespace(source, index[0]);
            if (index[0] < source.length() && source.charAt(index[0]) == ':') index[0]++;
            index[0] = skipWhitespace(source, index[0]);
            Object value = readValue(source, index);
            map.put(key, value);
            index[0] = skipWhitespace(source, index[0]);
            if (index[0] < source.length() && source.charAt(index[0]) == ',') index[0]++;
        }
        return map;
    }

    private static String readString(String source, int[] index) {
        index[0]++; // 越过开引号
        StringBuilder sb = new StringBuilder();
        while (index[0] < source.length()) {
            char c = source.charAt(index[0]);
            if (c == '\\') {
                index[0]++;
                if (index[0] < source.length()) {
                    char escaped = source.charAt(index[0]);
                    switch (escaped) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        default -> sb.append(escaped);
                    }
                    index[0]++;
                }
            } else if (c == '"') {
                index[0]++;
                break;
            } else {
                sb.append(c);
                index[0]++;
            }
        }
        return sb.toString();
    }

    private static int skipWhitespace(String source, int index) {
        while (index < source.length() && Character.isWhitespace(source.charAt(index))) index++;
        return index;
    }

    private static String jsonString(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
