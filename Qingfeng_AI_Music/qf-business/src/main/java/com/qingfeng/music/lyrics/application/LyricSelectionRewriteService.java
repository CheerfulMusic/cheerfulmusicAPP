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
    private static final System.Logger LOG = System.getLogger(LyricSelectionRewriteService.class.getName());
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
        return rewriter.rewriteSelection(owner, prompt.toString(), language, requestKey, MAX_PROMPT_LENGTH, writer -> {
                var accepted = new HashMap<Integer, String>();
                collect(writer, prompt.toString(), language, lines, targets, accepted, "initial");
                var pending = new HashSet<>(targets);
                pending.removeAll(accepted.keySet());
                if (!pending.isEmpty()) {
                    requireVersion(owner, work, version);
                    var context = lines.clone();
                    accepted.forEach((index, replacement) -> context[index] = replacement);
                    var repairPrompt = new StringBuilder("这是唯一一次补写。只重写 targetIndices 指定的问题句；其他行包含已合格的新句，只作上下文，不得改动。\n当前歌词：");
                    for (int index = 0; index < context.length; index++)
                        repairPrompt.append('\n').append(index).append('\t').append(context[index]);
                    if (repairPrompt.length() > MAX_PROMPT_LENGTH) throw invalidPreview();
                    collect(writer, repairPrompt.toString(), language, lines, pending, accepted, "repair");
                }
                if (accepted.size() != targets.size()) throw invalidPreview();
                var result = assemble(lines, accepted);
                requireVersion(owner, work, version);
                return result;
            });
    }

    private static void collect(InspirationRewriter writer, String prompt, String language, String[] source,
                                Set<Integer> targets, Map<Integer, String> accepted, String phase) {
        List<InspirationRewriter.Replacement> candidate;
        try {
            candidate = writer.rewriteSelection(prompt, language, targets.stream().sorted().toList());
        } catch (DomainException error) {
            if (!"INVALID_REWRITE_RESPONSE".equals(error.code())) throw error;
            // A received unusable response permits one repair; transport/unknown errors never do.
            LOG.log(System.Logger.Level.WARNING, "Lyric selection validation: phase={0}, reason=invalid_response, pending={1}", phase, targets.size());
            return;
        }
        var reasons = new HashMap<String, Integer>();
        var rows = new HashMap<Integer, InspirationRewriter.Replacement>();
        var duplicates = new HashSet<Integer>();
        if (candidate != null) for (var row : candidate) {
            if (row == null) { reasons.merge("malformed_row", 1, Integer::sum); continue; }
            int index = row.index();
            if (!targets.contains(index)) { reasons.merge("unexpected_index", 1, Integer::sum); continue; }
            if (rows.putIfAbsent(index, row) != null) duplicates.add(index);
        }
        int length = assemble(source, accepted).length();
        for (int index : targets.stream().sorted().toList()) {
            var row = rows.get(index);
            String reason = row == null ? "missing" : duplicates.contains(index) ? "duplicate" : row.text() == null ? "invalid_line" : null;
            if (reason != null) { reasons.merge(reason, 1, Integer::sum); continue; }
            String replacement = row.text().strip();
            if (!isLyric(replacement) || replacement.indexOf('\t') >= 0) reason = "invalid_line";
            else if (replacement.equals(source[index].strip())) reason = "unchanged";
            else if ((long) length + replacement.length() - source[index].length() > MAX_LYRICS_LENGTH) reason = "over_length";
            if (reason != null) { reasons.merge(reason, 1, Integer::sum); continue; }
            length += replacement.length() - source[index].length();
            accepted.put(index, replacement);
        }
        if (!reasons.isEmpty()) LOG.log(System.Logger.Level.WARNING,
            "Lyric selection validation: phase={0}, reasons={1}, accepted={2}", phase, reasons, accepted.size());
    }

    private static String assemble(String[] source, Map<Integer, String> replacements) {
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
