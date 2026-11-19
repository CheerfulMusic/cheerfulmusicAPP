package com.qingfeng.music.infrastructure.provider;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.lyrics.application.LyricsStructure;
import com.qingfeng.music.lyrics.application.LyricsWriter.SectionRepair;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import tools.jackson.core.JacksonException;

final class LyricsSectionRepair {
    private static final Duration WINDOW = Duration.ofSeconds(25);
    private static final String INSTRUCTIONS = """
        只补写 missingSections 中列出的段落。键是固定的段落位置，即使有同名段落也各自独立。
        用指定的歌词语言、用户要求和前后文写出可以演唱的歌词，延续原有故事与押韵风格。
        现有歌词和原稿仅作上下文。每个值只填该段歌词正文，包含真实换行，不加标题、段落标签或说明。
        只返回一个 JSON 对象，键和 missingSections 完全一致，值为歌词字符串。
        """;

    private LyricsSectionRepair() {}

    static String instructions(String language) { return INSTRUCTIONS + "\n歌词语言：" + LyricsWritingPrompt.language(language); }

    static Map<String, ?> material(SectionRepair request) {
        var work = request.work();
        return Map.of("title", request.draft().title(), "lyrics", request.draft().lyrics(),
            "missingSections", request.missing(), "revisionInstructions", request.instructions(),
            "previousLyrics", request.previousLyrics(), "recipient", work.recipient(), "occasion", work.occasion(),
            "story", work.story(), "message", work.message());
    }

    static Map<Integer, String> post(HttpClient client, URI endpoint, String key, String body, SectionRepair request,
                                     Function<String, String> outputText) {
        long deadline = LyricsQualityReview.deadline();
        Duration timeout;
        try {
            timeout = LyricsQualityReview.remaining(deadline, WINDOW);
        } catch (HttpTimeoutException exhausted) {
            throw LyricsStructure.mismatch();
        }
        try {
            var response = LyricsHttpTransport.post(client, endpoint, key, body, timeout);
            int status = response.statusCode();
            if (status == 408 || status == 504) throw timedOut();
            if (status >= 500) throw unknownConnection();
            if (status != 200) throw LyricsStructure.mismatch();
            var result = LyricsWritingPrompt.jsonObject(outputText.apply(response.body()));
            if (result.size() != request.missing().size()) throw LyricsStructure.mismatch();
            var sections = new LinkedHashMap<Integer, String>();
            for (int index : request.missing().keySet()) {
                var field = result.path(Integer.toString(index));
                if (!field.isString()) throw LyricsStructure.mismatch();
                sections.put(index, field.asText());
            }
            return sections;
        } catch (HttpTimeoutException error) {
            throw timedOut();
        } catch (IOException error) {
            throw unknownConnection();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new DomainException("LYRICS_REQUEST_INTERRUPTED", "歌词请求已中断，结果尚未确认，请先查看原请求进度。");
        } catch (JacksonException error) {
            throw LyricsStructure.mismatch();
        } catch (DomainException error) {
            if ("AGENT_DEADLINE_EXCEEDED".equals(error.code())) throw timedOut();
            if ("LYRICS_RESPONSE_INVALID".equals(error.code())) throw LyricsStructure.mismatch();
            throw error;
        }
    }

    private static DomainException timedOut() {
        return new DomainException("LYRICS_PROVIDER_TIMEOUT", "等待歌词结果超时，请先查看原请求进度。");
    }

    private static DomainException unknownConnection() {
        return new DomainException("LYRICS_PROVIDER_NETWORK_ERROR", "歌词服务连接中断，结果尚未确认，请先查看原请求进度。");
    }
}
