package com.qingfeng.music.lyrics.application;

import com.qingfeng.music.common.exception.DomainException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/** Analyzes one section and verifies the sound of each proposed replacement. */
public final class LyricRhymePreview {
    public record Request(String line, List<String> lines, int lineIndex, Integer referenceIndex,
                          String language, boolean allowNear, boolean allowCrossLanguage, boolean rewriteIfAlreadyRhymes) {}

    public enum Status { SUGGESTIONS, CHOOSE_REFERENCE, ALREADY_RHYMES, NO_MATCH, UNKNOWN_PRONUNCIATION }
    public record Ending(String word, String sound) {}
    public record Candidate(String text, Ending ending, LyricRhyme.Match match, Integer syllables) {}
    public record Result(Status status, Integer referenceIndex, Ending reference, Ending original, List<Candidate> candidates) {}

    private static final Set<String> LANGUAGES = Set.of("zh-CN", "en", "mixed");
    private static final List<String> SCHEMES = List.of("AAAA", "AABB", "ABAB", "ABBA", "XAXA", "AXAX");
    private static final Pattern SPOKEN_SCRIPT = Pattern.compile("(\\p{IsHan})|\\p{IsLatin}");

    private LyricRhymePreview() {}

    static Analysis analyze(Request request) {
        if (request.lines() == null || request.lines().isEmpty() || request.lines().size() > 40
            || !validIndex(request.lineIndex(), request.lines()) || !LyricLineRewriteService.isLyricLine(request.line())
            || request.lines().stream().anyMatch(line -> !LyricLineRewriteService.isLyricLine(line))
            || request.language() == null || !LANGUAGES.contains(request.language())
            || request.referenceIndex() != null && (!validIndex(request.referenceIndex(), request.lines())
                || request.referenceIndex() == request.lineIndex())) {
            throw new DomainException("INVALID_REWRITE_REQUEST", "请选择有效的歌词和参考句。");
        }

        var original = LyricRhyme.tail(request.line()).orElse(null);
        Integer referenceIndex = request.referenceIndex();
        if (referenceIndex == null) referenceIndex = inferReference(request);
        if (referenceIndex == null) return new Analysis(new Result(Status.CHOOSE_REFERENCE, null, null, ending(original), List.of()));

        String referenceLine = request.lines().get(referenceIndex);
        var reference = LyricRhyme.tail(referenceLine).orElse(null);
        if (original == null || reference == null) {
            return new Analysis(new Result(Status.UNKNOWN_PRONUNCIATION, referenceIndex, ending(reference), ending(original), List.of()));
        }
        var analysis = new Analysis(request, referenceIndex, original, reference, LyricRhyme.syllableCount(request.line()), null);
        if (!original.language().equals(reference.language()) && !request.allowCrossLanguage())
            return new Analysis(analysis.result(Status.NO_MATCH, List.of()));
        if (analysis.alreadyRhymes() && !request.rewriteIfAlreadyRhymes())
            return new Analysis(analysis.result(Status.ALREADY_RHYMES, List.of()));
        return analysis;
    }

    private static Integer inferReference(Request request) {
        int index = request.lineIndex();
        int from = index / 4 * 4;
        int count = Math.min(4, request.lines().size() - from);
        if (request.lines().size() == 2) return 1 - index;
        if (count < 3) return null;
        var tails = request.lines().subList(from, from + count).stream().map(line -> LyricRhyme.tail(line).orElse(null)).toList();
        int selected = index - from;
        int bestEvidence = 0;
        var references = new LinkedHashSet<Integer>();
        for (String scheme : SCHEMES) {
            char group = scheme.charAt(selected);
            if (group == 'X') continue;
            int evidence = evidence(scheme, tails, selected);
            if (evidence <= 0 || evidence < bestEvidence) continue;
            var options = IntStream.range(0, count).filter(i -> i != selected && scheme.charAt(i) == group && tails.get(i) != null)
                .boxed().sorted(Comparator.comparingInt(i -> Math.abs(i - selected))).toList();
            if (options.isEmpty()) continue;
            if (evidence > bestEvidence) references.clear();
            bestEvidence = evidence;
            references.add(options.get(0));
        }
        if (references.isEmpty()) return null;
        int first = references.iterator().next();
        return references.stream().allMatch(i -> LyricRhyme.match(tails.get(first), tails.get(i), false) == LyricRhyme.Match.EXACT)
            ? from + first : null;
    }

    private static int evidence(String scheme, List<LyricRhyme.Tail> tails, int selected) {
        int count = 0;
        for (int i = 0; i < tails.size(); i++) {
            if (i == selected || scheme.charAt(i) == 'X' || tails.get(i) == null) continue;
            for (int j = i + 1; j < tails.size(); j++) {
                if (j == selected || scheme.charAt(j) == 'X' || tails.get(j) == null) continue;
                boolean sameGroup = scheme.charAt(i) == scheme.charAt(j);
                boolean exact = LyricRhyme.match(tails.get(i), tails.get(j), false) == LyricRhyme.Match.EXACT;
                if (sameGroup != exact) return -1;
                if (exact && !sameEnding(tails.get(i), tails.get(j))) count++;
            }
        }
        return count;
    }

    private static boolean validIndex(int index, List<String> lines) { return index >= 0 && index < lines.size(); }

    private static Ending ending(LyricRhyme.Tail tail) {
        return tail == null ? null : new Ending(tail.word(), tail.pronunciation());
    }

    private static boolean sameEnding(LyricRhyme.Tail a, LyricRhyme.Tail b) {
        return comparable(a.word()).equals(comparable(b.word()))
            || a.language().equals("en") && b.language().equals("en") && a.pronunciation().equals(b.pronunciation());
    }

    private static String comparable(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    private static String languageRuns(String text) {
        var matcher = SPOKEN_SCRIPT.matcher(text);
        var runs = new StringBuilder();
        char previous = 0;
        while (matcher.find()) {
            char current = matcher.group(1) != null ? 'H' : 'L';
            if (current != previous) runs.append(current);
            previous = current;
        }
        return runs.toString();
    }

    record Analysis(Request request, Integer referenceIndex, LyricRhyme.Tail original,
                    LyricRhyme.Tail reference, int originalSyllables, Result result) {
        Analysis(Result result) { this(null, null, null, null, -1, result); }

        Result result(Status status, List<Candidate> candidates) {
            return new Result(status, referenceIndex, ending(reference), ending(original), candidates);
        }

        boolean alreadyRhymes() {
            return LyricRhyme.match(original, reference, false) == LyricRhyme.Match.EXACT && !sameEnding(original, reference);
        }

        String prompt() {
            var context = new StringBuilder();
            for (int i = 0; i < request.lines().size(); i++) {
                if (i == request.lineIndex()) continue;
                context.append(i + 1).append(". ").append(request.lines().get(i)).append('\n');
            }
            return "操作：RHYME_CANDIDATES\n待改写句：" + request.line()
                + "\n待改写行号：" + (request.lineIndex() + 1)
                + "\n参考行号：" + (referenceIndex + 1) + "\n参考句：" + request.lines().get(referenceIndex)
                + "\n目标韵脚：" + reference.word() + "（" + reference.pronunciation() + "，韵组 " + reference.key() + "）"
                + "\n原句尾音：" + original.word() + "（" + original.pronunciation() + "）"
                + "\n原句音节数：" + (originalSyllables < 0 ? "沿用原句节奏" : originalSyllables)
                + "\n允许同语言近韵：" + request.allowNear() + "；允许跨语言近韵：" + request.allowCrossLanguage()
                + (alreadyRhymes() ? "\n原句已经与参考句同韵。保留同韵并换一种自然表达，可以保留原句尾词。" : "")
                + "\n同段歌词（含参考句）：\n" + context;
        }

        Result select(String output) {
            boolean preserveExact = alreadyRhymes();
            String originalLanguages = languageRuns(request.line());
            var originalMatch = LyricRhyme.match(original, reference, request.allowCrossLanguage());
            var seen = new LinkedHashSet<String>();
            seen.add(comparable(request.line()));
            var candidates = new ArrayList<Candidate>();
            for (String raw : output.split("\\R")) {
                String text = raw.strip();
                if (!LyricLineRewriteService.isLyricLine(text) || !seen.add(comparable(text))
                    || !languageRuns(text).equals(originalLanguages)) continue;
                var tail = LyricRhyme.tail(text).orElse(null);
                if (tail == null || !Objects.equals(tail.language(), original.language())
                    || (!preserveExact && sameEnding(tail, original)) || sameEnding(tail, reference)) continue;
                var match = LyricRhyme.match(tail, reference, request.allowCrossLanguage());
                if (preserveExact && match != LyricRhyme.Match.EXACT) continue;
                if (match == LyricRhyme.Match.NONE || match == LyricRhyme.Match.NEAR && !request.allowNear()) continue;
                if (match != LyricRhyme.Match.EXACT && match == originalMatch && tail.key().equals(original.key())) continue;
                int syllables = LyricRhyme.syllableCount(text);
                if (originalSyllables >= 0 && syllables >= 0 && Math.abs(syllables - originalSyllables) > 1) continue;
                candidates.add(new Candidate(text, ending(tail), match, syllables < 0 ? null : syllables));
            }
            var sorted = candidates.stream().sorted(Comparator
                .comparing((Candidate candidate) -> candidate.match() != LyricRhyme.Match.EXACT)
                .thenComparingInt(candidate -> originalSyllables < 0 || candidate.syllables() == null
                    ? Integer.MAX_VALUE : Math.abs(candidate.syllables() - originalSyllables)))
                .limit(3).toList();
            return result(sorted.isEmpty() ? Status.NO_MATCH : Status.SUGGESTIONS, sorted);
        }
    }
}
