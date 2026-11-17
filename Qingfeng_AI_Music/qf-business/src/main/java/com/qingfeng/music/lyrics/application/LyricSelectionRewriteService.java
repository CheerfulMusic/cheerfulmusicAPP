package com.qingfeng.music.lyrics.application;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.work.api.WorkApi;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Produces a preview from the current editor draft. Saving the draft is a separate action. */
public final class LyricSelectionRewriteService {
    private static final int MAX_LYRICS_LENGTH = 12000;
    private static final int MAX_PROMPT_LENGTH = 100000;
    private static final Pattern SECTION = Pattern.compile("^\\s*\\[.*\\]\\s*$", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern BLANK = Pattern.compile("^\\s*$", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern LINE_BREAK = Pattern.compile("\\R");
    private final WorkApi works;
    private final InspirationRewriteService rewriter;

    public LyricSelectionRewriteService(WorkApi works, InspirationRewriteService rewriter) {
        this.works = works;
        this.rewriter = rewriter;
    }

    public String preview(String owner, String work, long version, UUID requestId, String source,
                          List<Integer> keptLines, String language) {
        requireVersion(owner, work, version);
        if (source == null || source.isBlank() || source.length() > MAX_LYRICS_LENGTH || keptLines == null) throw invalidSelection();
        var lines = source.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        var kept = new HashSet<>(keptLines);
        if (kept.isEmpty() || kept.size() != keptLines.size() || kept.stream().anyMatch(index ->
                index == null || index < 0 || index >= lines.length || !isLyric(lines[index]))) throw invalidSelection();

        var targets = new HashSet<Integer>();
        var prompt = new StringBuilder("原样保留的行号：").append(kept.stream().sorted().toList()).append("\n原歌词：");
        for (int index = 0; index < lines.length; index++) {
            prompt.append('\n').append(index).append('\t').append(lines[index]);
            if (isLyric(lines[index]) && !kept.contains(index)) targets.add(index);
        }
        if (targets.isEmpty()) throw invalidSelection();

        // Keep the existing key derivation for retries across server updates.
        var material = "lyric-selection\n" + work + '\n' + version + '\n' + requestId + '\n' + language + '\n' + prompt;
        var requestKey = UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8)).toString();
        return rewriter.rewriteSelection(owner, prompt.toString(), language, requestKey, MAX_PROMPT_LENGTH,
            targets.stream().sorted().toList(), candidate -> {
                var result = assemble(lines, targets, candidate);
                requireVersion(owner, work, version);
                return result;
            });
    }

    private static String assemble(String[] source, Set<Integer> targets, List<InspirationRewriter.Replacement> candidate) {
        if (candidate == null || candidate.size() != targets.size()) throw invalidPreview();
        Map<Integer, String> replacements = new HashMap<>();
        for (var row : candidate) {
            if (row == null || row.text() == null) throw invalidPreview();
            int index = row.index();
            String replacement = row.text().strip();
            if (!targets.contains(index) || !isLyric(replacement) || replacement.indexOf('\t') >= 0
                    || replacement.equals(source[index].strip())
                    || replacements.putIfAbsent(index, replacement) != null) throw invalidPreview();
        }
        var result = source.clone();
        replacements.forEach((index, replacement) -> result[index] = replacement);
        String lyrics = String.join("\n", result);
        if (lyrics.length() > MAX_LYRICS_LENGTH) throw invalidPreview();
        return lyrics;
    }

    private void requireVersion(String owner, String work, long version) {
        if (works.get(owner, work).version() != version)
            throw new DomainException("WORK_VERSION_CONFLICT", "作品已修改，请重新载入后保存歌词");
    }

    private static boolean isLyric(String line) {
        return !BLANK.matcher(line).matches() && !SECTION.matcher(line).matches() && !LINE_BREAK.matcher(line).find();
    }

    private static DomainException invalidSelection() {
        return new DomainException("INVALID_REWRITE_REQUEST", "请至少保留一句，并留出需要重写的歌词。");
    }

    private static DomainException invalidPreview() {
        return new DomainException("INVALID_REWRITE_RESPONSE", "重写结果不完整，当前歌词已保留，请重试。");
    }
}
