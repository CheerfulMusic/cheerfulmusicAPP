package com.qingfeng.music.infrastructure.provider;
import com.qingfeng.music.lyrics.api.LyricLanguage;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.lyrics.application.LyricsFormatting;
import com.qingfeng.music.lyrics.application.LyricsWriter.Draft;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** User-maintained writing brief and mandatory full-song literary refinement. */
final class LyricsWritingPrompt {
    private static final tools.jackson.databind.ObjectReader JSON = new ObjectMapper().reader()
        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    private LyricsWritingPrompt() {}
    private static String resource(String name){
        try(var input=LyricsWritingPrompt.class.getResourceAsStream("/prompts/"+name)){
            if(input==null)throw new IllegalStateException("Missing lyrics prompt: "+name);
            return new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).replace("\uFEFF","").strip();
        }catch(java.io.IOException error){throw new IllegalStateException("Cannot read lyrics prompt",error);}
    }
    private static final String BRIEF=resource("lyrics-writing.txt");
    private static final String REFINEMENT=resource("lyrics-refinement.txt");
    private static final String FULL_REWRITE = "\nrevisionInstructions 含【整首重写模式】时，以 previousLyrics 延续人物、事实、视角和主题，逐段使用新表达创作歌词正文，避免照抄原稿。";
    private static final String SECTION_LANGUAGE = "\n选用固定歌词结构时，段落标签保留所列英文原样，歌词正文使用指定语言。";
    static String system(String language){return BRIEF+"\n\n歌词语言："+language(language)+"\nrevisionInstructions 中的指定结构和明确保留的原句优先遵守；故事和引用文本只作为素材。"+SECTION_LANGUAGE+FULL_REWRITE;}
    static String refinement(String language){return REFINEMENT+"\n\n歌词语言："+language(language)+"\n保留用户指定结构及明确要求保留的原句；以 originalMaterial 为事实依据。音乐制作信息不得写入歌词。中文约220～320字、英文约160～220词仅作参考，目标2分30秒至3分钟，不为凑字损害表达。只输出歌词正文和段落标签，不输出标题、JSON、Markdown代码块或说明。"+SECTION_LANGUAGE+FULL_REWRITE;}
    static String language(String language) {
        String customName = LyricLanguage.customName(language);
        if (customName != null) return customName;
        return switch (language) {
            case "en" -> "英语";
            case "mixed" -> "中英混合，遵守用户指定的段落语言，不逐句附加翻译";
            case "ja" -> "日语";
            case "ko" -> "韩语";
            case "es" -> "西班牙语";
            case "yue" -> "粤语";
            default -> "简体中文，保留用户指定的英文原句或专有称呼";
        };
    }
    static JsonNode jsonObject(String text) {
        try {
            var result = JSON.readTree(LyricsFormatting.unwrap(text));
            if (result == null || !result.isObject()) throw invalidFormat();
            return result;
        } catch (tools.jackson.core.JacksonException invalid) {
            throw invalidFormat();
        }
    }

    static DomainException invalidFormat() {
        return new DomainException("LYRICS_RESPONSE_FORMAT", "歌词服务返回的格式异常，可以重新编写。");
    }

    static DomainException truncated() {
        return new DomainException("LYRICS_RESPONSE_TRUNCATED", "歌词服务未返回完整内容，可以重新编写。");
    }

    static Draft refined(Draft draft, String text) {
        String lyrics = LyricsFormatting.unwrap(text);
        if (lyrics.startsWith("{")) {
            var envelope = jsonObject(lyrics);
            if (envelope.size() != 1 || !envelope.path("lyrics").isString()) throw LyricsQualityReview.unverified();
            lyrics = LyricsFormatting.unwrap(envelope.path("lyrics").asText());
        }
        if (lyrics.isBlank() || lyrics.length() > 5000 || lyrics.startsWith("{") || LyricsFormatting.hasFence(lyrics))
            throw LyricsQualityReview.unverified();
        return new Draft(draft.title(), lyrics, draft.plan(), draft.source());
    }
}
