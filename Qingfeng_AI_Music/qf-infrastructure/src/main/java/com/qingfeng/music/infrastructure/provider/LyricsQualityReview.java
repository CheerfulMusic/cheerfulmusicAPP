package com.qingfeng.music.infrastructure.provider;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.lyrics.application.LyricsWriter;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** Validates an editor's bounded, cited selection. This is not an objective guarantee of artistic quality. */
final class LyricsQualityReview {
    static final Duration GENERATION_TIMEOUT = Duration.ofSeconds(75);
    private static final Duration TOTAL_TIMEOUT = Duration.ofSeconds(110);
    private static final Set<String> DECISIONS = Set.of("KEEP", "REWRITE", "REJECT", "UNSURE");
    private static final Set<String> ISSUE_CODES = Set.of("FACT_OR_CONSTRAINT", "HOOK", "PROGRESSION", "NATURAL_LANGUAGE", "RHYTHM", "STYLE");
    private static final List<String> POSITIVE_QUOTES = List.of("hookQuote", "progressionQuote", "rhythmQuote");
    private static final Map<String, Integer> PLAN_FIELDS = Map.of("requirements", 600, "suggestedArrangement", 240,
        "negativeTags", 250, "summaryZh", 200, "summaryEn", 300, "vocalGender", 6);
    private LyricsQualityReview() {}

    static long deadline() { return com.qingfeng.music.common.concurrent.LyricsRequestBudget.deadline(TOTAL_TIMEOUT); }

    static Duration remaining(long deadline, Duration maximum) throws HttpTimeoutException {
        long left = deadline - System.nanoTime();
        if (left <= 0) throw new HttpTimeoutException("Lyrics creation budget exhausted");
        return Duration.ofNanos(Math.min(left, maximum.toNanos()));
    }

    static Duration remaining(long deadline) throws HttpTimeoutException { return remaining(deadline, TOTAL_TIMEOUT); }

    static Duration editorialBudget(long deadline) { return phaseBudget(deadline, Duration.ofSeconds(20)); }
    static Duration comparisonBudget(long deadline) { return phaseBudget(deadline, Duration.ZERO); }

    private static Duration phaseBudget(long deadline, Duration reserve) {
        try {
            Duration remaining = com.qingfeng.music.common.concurrent.AgentRequestScope.timeout(remaining(deadline));
            long available = remaining.toNanos() - reserve.toNanos();
            if (available <= 0) throw unverified();
            return Duration.ofNanos(available);
        } catch (HttpTimeoutException | DomainException insufficient) {
            // No request for this phase has started; an exhausted budget cannot justify delivering an unreviewed draft.
            throw unverified();
        }
    }

    static Map<String, Object> candidate(LyricsWriter.Draft draft) {
        var plan = draft.plan();
        return Map.of("title", draft.title(), "lyrics", draft.lyrics(), "plan", Map.of(
            "requirements", plan.requirements(), "suggestedArrangement", plan.suggestedArrangement(),
            "negativeTags", plan.negativeTags(), "summaryZh", plan.summaryZh(),
            "summaryEn", plan.summaryEn(), "vocalGender", plan.vocalGender()));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> schema(Map<String, Object> songSchema) {
        var fields = new LinkedHashMap<String, Object>((Map<String, Object>) songSchema.get("properties"));
        var issue = object(Map.of("code", Map.of("type", "string", "enum", ISSUE_CODES.stream().sorted().toList()),
            "quote", string(1000), "reason", string(1000)));
        var quality = new LinkedHashMap<String, Object>();
        quality.put("decision", Map.of("type", "string", "enum", DECISIONS.stream().sorted().toList()));
        quality.put("issues", Map.of("type", "array", "items", issue, "maxItems", 8));
        for (String name : POSITIVE_QUOTES) quality.put(name, string(1000));
        quality.put("reason", string(1000));
        fields.put("quality", object(quality));
        return object(fields);
    }

    static boolean originalIsA(String workId) { return (Objects.hashCode(workId) & 1) == 0; }

    static Map<String, Object> comparisonInput(Map<String, Object> material, LyricsWriter.Draft original,
                                              LyricsWriter.Draft edited, boolean originalIsA) {
        var a = originalIsA ? original : edited;
        var b = originalIsA ? edited : original;
        return Map.of("originalMaterial", material, "candidates", Map.of(
            "A", Map.of("title", a.title(), "lyrics", a.lyrics()),
            "B", Map.of("title", b.title(), "lyrics", b.lyrics())));
    }

    static Map<String, Object> comparisonSchema() {
        var assessment = object(Map.of("acceptable", Map.of("type", "boolean"),
            "quote", string(1000), "reason", string(1000)));
        var properties = new LinkedHashMap<String, Object>();
        properties.put("choice", Map.of("type", "string", "enum", List.of("A", "B", "NEITHER", "UNSURE")));
        properties.put("a", assessment);
        properties.put("b", assessment);
        for (String name : POSITIVE_QUOTES) properties.put(name, string(1000));
        properties.put("reason", string(1000));
        return object(properties);
    }

    static LyricsWriter.Draft selectComparison(JsonNode verdict, LyricsWriter.Draft original,
                                               LyricsWriter.Draft edited, boolean originalIsA) {
        fields(verdict, Set.of("choice", "a", "b", "hookQuote", "progressionQuote", "rhythmQuote", "reason"));
        String choice = text(verdict, "choice", 7, false);
        require(Set.of("A", "B", "NEITHER", "UNSURE").contains(choice));
        text(verdict, "reason", 1000, false);
        LyricsWriter.Draft a = originalIsA ? original : edited;
        LyricsWriter.Draft b = originalIsA ? edited : original;
        boolean aAcceptable = assessment(verdict.path("a"), a);
        boolean bAcceptable = assessment(verdict.path("b"), b);
        if (choice.equals("NEITHER") || choice.equals("UNSURE")) {
            for (String name : POSITIVE_QUOTES) require(text(verdict, name, 1000, true).isEmpty());
            if (choice.equals("UNSURE")) throw unverified();
            require(!aAcceptable && !bAcceptable);
            throw notMet();
        }
        LyricsWriter.Draft selected = choice.equals("A") ? a : b;
        require(choice.equals("A") ? aAcceptable : bAcceptable);
        for (String name : POSITIVE_QUOTES) positiveReference(text(verdict, name, 1000, false), selected.lyrics());
        return new LyricsWriter.Draft(original.title(), selected.lyrics(), original.plan(), original.source());
    }

    private static boolean assessment(JsonNode assessment, LyricsWriter.Draft candidate) {
        fields(assessment, Set.of("acceptable", "quote", "reason"));
        require(assessment.path("acceptable").isBoolean());
        require(candidate.lyrics().contains(text(assessment, "quote", 1000, false)));
        text(assessment, "reason", 1000, false);
        return assessment.path("acceptable").asBoolean();
    }

    static LyricsWriter.Draft validate(JsonNode reviewed, LyricsWriter.Draft original) {
        fields(reviewed, Set.of("title", "lyrics", "plan", "quality"));
        String title = text(reviewed, "title", 80, false);
        String selected = text(reviewed, "lyrics", 5000, false);
        require(title.equals(original.title()));
        JsonNode plan = reviewed.path("plan");
        fields(plan, PLAN_FIELDS.keySet());
        for (var field : PLAN_FIELDS.entrySet()) text(plan, field.getKey(), field.getValue(), true);
        require(Set.of("MALE", "FEMALE", "AUTO").contains(plan.path("vocalGender").asText()));

        JsonNode quality = reviewed.path("quality");
        fields(quality, Set.of("decision", "issues", "hookQuote", "progressionQuote", "rhythmQuote", "reason"));
        String decision = text(quality, "decision", 7, false);
        require(DECISIONS.contains(decision));
        text(quality, "reason", 1000, false);
        JsonNode issues = quality.path("issues");
        require(issues.isArray() && issues.size() <= 8);
        for (JsonNode issue : issues) {
            fields(issue, Set.of("code", "quote", "reason"));
            require(ISSUE_CODES.contains(text(issue, "code", 24, false)));
            String quote = text(issue, "quote", 1000, false);
            text(issue, "reason", 1000, false);
            require(original.lyrics().contains(quote));
        }
        boolean success = decision.equals("KEEP") || decision.equals("REWRITE");
        for (String name : POSITIVE_QUOTES) {
            String quote = text(quality, name, 1000, !success);
            if (!quote.isEmpty()) positiveReference(quote, selected);
        }
        if (decision.equals("UNSURE")) throw unverified();
        if (decision.equals("REJECT")) {
            require(!issues.isEmpty());
            throw notMet();
        }
        require(sectionLabels(original.lyrics()).equals(sectionLabels(selected)));
        if (decision.equals("KEEP")) {
            require(issues.isEmpty() && selected.equals(original.lyrics()));
            return original;
        }
        require(!issues.isEmpty() && !selected.replaceAll("\\s+", "").equals(original.lyrics().replaceAll("\\s+", "")));
        // Editing words never grants permission to change the title, music plan, or source attribution.
        return new LyricsWriter.Draft(original.title(), selected, original.plan(), original.source());
    }

    private static List<String> sectionLabels(String lyrics) {
        return java.util.regex.Pattern.compile("(?m)^\\s*\\[([^]\\r\\n]+)]\\s*$")
            .matcher(lyrics).results().map(match -> match.group(1).trim()).toList();
    }

    private static void positiveReference(String quote, String lyrics) {
        require(!quote.isBlank() && lyrics.contains(quote));
        require(!quote.replaceAll("(?m)^\\s*\\[[^]\\r\\n]+]\\s*$", "").isBlank());
    }

    private static void fields(JsonNode node, Set<String> expected) {
        require(node.isObject() && node.size() == expected.size());
        for (String name : expected) require(node.has(name));
    }

    private static String text(JsonNode node, String name, int maximum, boolean emptyAllowed) {
        JsonNode value = node.path(name);
        require(value.isString());
        String result = value.asText();
        require(result.length() <= maximum && (emptyAllowed || !result.isBlank()));
        return result;
    }

    private static Map<String, Object> string(int max) { return Map.of("type", "string", "maxLength", max); }
    private static Map<String, Object> object(Map<String, Object> fields) {
        return Map.of("type", "object", "properties", fields, "required", List.copyOf(fields.keySet()), "additionalProperties", false);
    }
    private static void require(boolean ok) { if (!ok) throw unverified(); }
    private static DomainException notMet() {
        return new DomainException("LYRICS_QUALITY_NOT_MET", "这次歌词未通过创作审查，已有内容和素材仍会保留。");
    }
    static DomainException unverified() {
        return new DomainException("LYRICS_QUALITY_UNVERIFIED", "本次歌词审查未完成，未交付新版本；已有内容和素材仍会保留。");
    }
}
