package com.qingfeng.music.lyrics.application;

import com.qingfeng.music.common.exception.DomainException;
import java.util.*;
import java.util.regex.Pattern;
import java.text.Normalizer;

/** User-selected section order is a contract, not a reusable melody or lyric text. */
public record LyricsStructure(String templateId, boolean chorusFirst, List<String> customSections) {
    public static final int MAX_CUSTOM_SECTIONS = 24;
    private static final Set<String> CUSTOM_SECTION_NAMES = Set.of(
        "Verse", "Verse 1", "Verse 2", "Pre-Chorus", "Chorus", "Chorus 2", "Bridge", "Interlude");
    private static final Pattern HEADER = Pattern.compile("(?m)^\\h*\\[([^]\\r\\n]+)]\\h*$");
    private static final Pattern STYLED_HEADER = Pattern.compile("^(?:#{1,6}\\h+)?(?:\\*\\*|__)?\\[([^]\\r\\n]+)](?:\\*\\*|__)?\\h*[:：]?$");
    private static final Pattern LYRIC_TEXT = Pattern.compile("[\\p{L}\\p{N}]");
    private static final Map<String, String> SECTION_ALIASES = Map.of(
        "主歌", "Verse", "主歌1", "Verse 1", "主歌2", "Verse 2", "预副歌", "Pre-Chorus",
        "副歌", "Chorus", "副歌2", "Chorus 2", "桥段", "Bridge", "间奏", "Interlude", "prechorus", "Pre-Chorus");
    private static final Map<String,List<String>> TEMPLATES = Map.of(
        "AAB", List.of("Verse 1","Verse 2","Chorus","Interlude","Verse 2","Chorus","Chorus 2"),
        "ABB", List.of("Verse","Chorus","Chorus 2","Interlude","Verse","Chorus","Chorus 2"),
        "ABC", List.of("Verse","Pre-Chorus","Chorus","Interlude","Pre-Chorus","Chorus","Chorus 2"),
        "ABCD", List.of("Verse","Pre-Chorus","Chorus","Interlude","Pre-Chorus","Chorus","Bridge","Chorus"));

    public LyricsStructure(String templateId, boolean chorusFirst) {
        this(templateId, chorusFirst, null);
    }

    public LyricsStructure {
        templateId = templateId == null ? "AUTO" : templateId;
        if (templateId.equals("CUSTOM")) {
            if (chorusFirst || customSections == null || customSections.size() > MAX_CUSTOM_SECTIONS
                || customSections.stream().anyMatch(section -> section == null || !CUSTOM_SECTION_NAMES.contains(section))
                || customSections.stream().allMatch("Interlude"::equals)) {
                throw new DomainException("INVALID_LYRICS_REQUEST", "请设置有效的自定义段落顺序，最多24段，且至少有一段填写歌词");
            }
            customSections = List.copyOf(customSections);
        } else {
            if (!templateId.equals("AUTO") && !TEMPLATES.containsKey(templateId) || templateId.equals("AUTO") && chorusFirst)
                throw new DomainException("INVALID_LYRICS_REQUEST", "请选择有效的歌词模板；副歌先行需要先选择一种模板");
            if (customSections != null && !customSections.isEmpty())
                throw new DomainException("INVALID_LYRICS_REQUEST", "请先选择自定义歌词模板");
            customSections = null;
        }
    }
    public List<String> sections() {
        if (customSections != null) return customSections;
        var result = new ArrayList<>(TEMPLATES.getOrDefault(templateId,List.of()));
        if (chorusFirst) { result.remove("Chorus"); result.add(0,"Chorus"); }
        return List.copyOf(result);
    }
    public String instruction() {
        if (templateId.equals("AUTO")) return "";
        return "\n【段落结构约束】\n必须严格按以下段落顺序输出，不增删或合并段落，每个标签独占一行：\n"
            + String.join(" → ", sections().stream().map(s -> "["+s+"]").toList())
            + "\n[Interlude]为器乐间奏，只输出该标签，不填写歌词或说明。其余每段必须有歌词。"
            + "Chorus 2是有发展变化的第二副歌，不是Bridge。重复段落保留对应标签。"
            + "可保持副歌记忆点，主歌推动叙事；模板只规定结构，不套用固定歌词。"
            + (chorusFirst ? "副歌先行已经体现在上述顺序中：首个Chorus移到开头，原位置不再重复添加。" : "");
    }
    public void validate(String lyrics) {
        if (templateId.equals("AUTO")) return;
        var parsed = parse(lyrics);
        var expected = sections();
        if (parsed.size() != expected.size()) throw mismatch();
        for (int i = 0; i < expected.size(); i++) {
            var section = parsed.get(i);
            if (!expected.get(i).equalsIgnoreCase(section.name()) || instrumental(section.name()) != section.body().isBlank())
                throw mismatch();
        }
    }

    static String normalizeHeaders(String lyrics) {
        return String.join("\n", Arrays.stream(lyrics.split("\n", -1)).map(LyricsStructure::canonicalHeader).toList());
    }

    private static String canonicalHeader(String line) {
        String normalized = Normalizer.normalize(line.strip(), Normalizer.Form.NFKC)
            .replace('【', '[').replace('】', ']');
        var match = STYLED_HEADER.matcher(normalized);
        if (!match.matches()) return line;
        String name = match.group(1).strip().replaceAll("\\h+", " ").toLowerCase(Locale.ROOT);
        String alias = SECTION_ALIASES.get(name.replace(" ", ""));
        if (alias != null) return "[" + alias + "]";
        for (String known : CUSTOM_SECTION_NAMES) {
            if (known.equalsIgnoreCase(name)) return "[" + known + "]";
        }
        return line;
    }

    Prepared prepare(String lyrics) {
        var expected = sections();
        if (expected.isEmpty()) return new Prepared(lyrics, expected, Map.of(), Map.of());
        var parsed = parse(lyrics);
        int[] first = positions(expected, parsed, 1);
        int[] last = positions(expected, parsed, -1);
        // The first and last in-order alignments must agree, including when a label repeats.
        if (!Arrays.equals(first, last)) throw mismatch();
        var bodies = new LinkedHashMap<Integer, String>();
        for (int i = 0; i < parsed.size(); i++) {
            var section = parsed.get(i);
            if (instrumental(section.name()) && !section.body().isBlank()) throw mismatch();
            bodies.put(first[i], section.body());
        }
        var missing = new LinkedHashMap<Integer, String>();
        for (int i = 0; i < expected.size(); i++) {
            if (!instrumental(expected.get(i)) && bodies.getOrDefault(i, "").isBlank()) missing.put(i, expected.get(i));
        }
        if (bodies.values().stream().noneMatch(body -> !body.isBlank())) throw mismatch();
        return new Prepared(lyrics, expected, Collections.unmodifiableMap(bodies), Collections.unmodifiableMap(missing));
    }

    private static int[] positions(List<String> expected, List<Section> parsed, int step) {
        int[] positions = new int[parsed.size()];
        int index = step > 0 ? 0 : expected.size() - 1;
        for (int i = step > 0 ? 0 : parsed.size() - 1; i >= 0 && i < parsed.size(); i += step) {
            while (index >= 0 && index < expected.size() && !expected.get(index).equalsIgnoreCase(parsed.get(i).name())) index += step;
            if (index < 0 || index >= expected.size()) throw mismatch();
            positions[i] = index;
            index += step;
        }
        return positions;
    }

    private static List<Section> parse(String lyrics) {
        var headers = HEADER.matcher(lyrics);
        var parsed = new ArrayList<Section>();
        String previous = null;
        int start = 0;
        while (headers.find()) {
            String body = lyrics.substring(start, headers.start());
            if (previous != null) parsed.add(new Section(previous, body));
            else if (!body.isBlank()) throw mismatch();
            previous = headers.group(1).strip();
            start = headers.end();
        }
        if (previous == null) throw mismatch();
        parsed.add(new Section(previous, lyrics.substring(start)));
        return parsed;
    }

    private record Section(String name, String body) {}
    private static boolean instrumental(String section) { return "Interlude".equalsIgnoreCase(section); }

    record Prepared(String original, List<String> expected, Map<Integer, String> bodies, Map<Integer, String> missing) {
        String complete(Map<Integer, String> replacements) {
            if (replacements == null || !replacements.keySet().equals(missing.keySet())) throw mismatch();
            if (bodies.size() == expected.size() && missing.isEmpty()) return original;
            var result = new StringBuilder();
            for (int i = 0; i < expected.size(); i++) {
                if (i > 0 && result.charAt(result.length() - 1) != '\n') result.append('\n');
                result.append('[').append(expected.get(i)).append(']');
                if (missing.containsKey(i)) {
                    String replacement = replacements.get(i);
                    if (replacement == null || replacement.length() > 5000) throw mismatch();
                    String body = LyricsFormatting.unwrap(replacement);
                    if (!LYRIC_TEXT.matcher(body).find() || HEADER.matcher(normalizeHeaders(body)).find()
                            || LyricsFormatting.hasFence(body) || body.startsWith("{")) throw mismatch();
                    result.append('\n').append(body);
                } else result.append(bodies.getOrDefault(i, ""));
            }
            return result.toString();
        }
    }

    public static DomainException mismatch() {
        return new DomainException("LYRICS_STRUCTURE_MISMATCH", "本次歌词未符合所选模板，原歌词已保留，可以重新生成。");
    }
}
