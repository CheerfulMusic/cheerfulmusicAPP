package com.qingfeng.music.lyrics.application;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.work.api.WorkApi;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/** A short-lived preview never creates a lyric revision. */
public final class LyricLineRewriteService {
    public enum Action { REWRITE, RHYME, COLLOQUIAL, SHORTEN }
    private record Key(String owner, UUID requestId) {}
    private record Entry(String fingerprint, Instant expiresAt, boolean pending, String result) {}
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int MAX_ENTRIES = 10_000;
    private final Map<Key, Entry> requests = new HashMap<>();
    private final WorkApi works;
    private final InspirationRewriteService rewriter;
    private final int perOwner;

    public LyricLineRewriteService(WorkApi works, InspirationRewriteService rewriter, int perOwner) {
        if (perOwner < 1) throw new IllegalArgumentException("Lyric rewrite limit must be positive");
        this.works = works;
        this.rewriter = rewriter;
        this.perOwner = perOwner;
    }

    public String preview(String owner, String work, UUID requestId, Action action, String line, String context, String language) {
        works.get(owner, work);
        if (!isLyricLine(line)) throw invalid();
        var key = new Key(owner, requestId);
        var input = "操作：" + action.name() + "\n待改写句：" + line + "\n相邻歌词：" + (context==null?"":context);
        var fingerprint = digest(work + "\n" + language + "\n" + input);
        synchronized (requests) {
            var now = Instant.now();
            requests.entrySet().removeIf(item -> !item.getValue().pending() && !item.getValue().expiresAt().isAfter(now));
            var existing = requests.get(key);
            if (existing != null) {
                if (!existing.fingerprint().equals(fingerprint))
                    throw new DomainException("REQUEST_KEY_CONFLICT", "改写内容已变化，请重新选择歌词。");
                if (existing.result()!=null) return existing.result();
                if (!existing.pending()) throw invalid();
                throw busy();
            }
            long used = 0;
            for (var item : requests.entrySet()) {
                if (!item.getKey().owner().equals(owner)) continue;
                if (item.getValue().pending()) throw busy();
                used++;
            }
            if (requests.size() >= MAX_ENTRIES || used >= perOwner)
                throw new DomainException("LYRIC_REWRITE_LIMIT", "短时间内改写次数较多，请稍后再试。");
            requests.put(key, new Entry(fingerprint, now.plus(WINDOW), true, null));
        }
        String result = null;
        try {
            var candidate = rewriter.rewrite(owner, input, language);
            if (!isLyricLine(candidate)) throw invalid();
            if (action==Action.SHORTEN && candidate.length()>=line.strip().length()) throw invalid();
            result = candidate;
            return result;
        } finally {
            synchronized (requests) {
                requests.put(key, new Entry(fingerprint, requests.get(key).expiresAt(), false, result));
            }
        }
    }

    private static boolean isLyricLine(String text) {
        return text!=null && !text.isBlank() && text.length()<=160 &&
            !text.matches("(?s).*\\R.*") && !text.matches("\\s*\\[.*]\\s*");
    }
    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static DomainException invalid() {
        return new DomainException("INVALID_REWRITE_RESPONSE", "未得到可用的单句改写，原歌词已保留。");
    }
    private static DomainException busy() {
        return new DomainException("REWRITE_GENERATION_BUSY", "AI改写正在处理中，请稍后再试。");
    }
}
