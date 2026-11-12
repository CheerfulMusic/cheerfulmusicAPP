package com.qingfeng.music.agent.application;

import com.qingfeng.music.agent.api.AgentTool;
import com.qingfeng.music.agent.model.StyleCatalog;
import com.qingfeng.music.common.exception.DomainException;

import java.util.Set;

/**
 * 白名单 + 参数校验。W2 唯一允许新增的「规则」层，不调用任何业务 Service。
 * - 语言：用户裁决取 zh / en / yue
 * - style：走 StyleCatalog 站内白名单（越界即拒，系统提示词已约束 LLM 只能从中选）
 * - bpm：40–220
 * - 文本类参数：≤ 4000 字
 * 校验失败抛 DomainException，code 以 INVALID_ 开头 → 由全局异常处理器映射为 HTTP 400。
 */
public final class AgentToolRegistry {

    private static final Set<String> LANGUAGES = Set.of("zh", "en", "yue");
    private static final int MAX_TEXT = 4000;
    private static final int MIN_BPM = 40;
    private static final int MAX_BPM = 220;

    public void validate(AgentTool tool, Object args) {
        switch (tool) {
            case GENERATE_SONG -> validateGenerateSong((AgentTool.GenerateSongArgs) args);
            case GENERATE_LYRICS -> validateGenerateLyrics((AgentTool.GenerateLyricsArgs) args);
            case MODIFY_SECTION -> validateModifySection((AgentTool.ModifySectionArgs) args);
            case EXTEND_SONG -> validateExtendSong((AgentTool.ExtendSongArgs) args);
            case SET_STYLE -> validateSetStyle((AgentTool.SetStyleArgs) args);
            case GET_CURRENT_SONG -> { /* 无参数 */ }
        }
    }

    private void requireText(String name, String value) {
        if (value != null && value.length() > MAX_TEXT) {
            throw new DomainException("INVALID_TOOL_ARGUMENT", name + " 过长（上限 " + MAX_TEXT + " 字）。");
        }
    }

    private void requireLanguage(String value) {
        if (value != null && !value.isBlank() && !LANGUAGES.contains(value.trim())) {
            throw new DomainException("INVALID_LANGUAGE", "语言仅支持 zh / en / yue。");
        }
    }

    private void requireStyle(String style) {
        if (style != null && !style.isBlank() && !StyleCatalog.allows(style)) {
            throw new DomainException("INVALID_STYLE", "风格不在站内白名单：" + style);
        }
    }

    private void requireBpm(Integer bpm) {
        if (bpm != null && (bpm < MIN_BPM || bpm > MAX_BPM)) {
            throw new DomainException("INVALID_BPM", "BPM 需在 " + MIN_BPM + "–" + MAX_BPM + " 之间。");
        }
    }

    private void validateGenerateSong(AgentTool.GenerateSongArgs a) {
        requireText("text", a.text());
        requireStyle(a.style());
        requireLanguage(a.language());
        requireBpm(a.bpm());
        requireMode(a.mode(), Set.of("new", "regenerate"));
        requireInstruments(a.instruments());
        if (a.vocal() != null && !Set.of("AUTO", "MALE", "FEMALE", "auto", "male", "female").contains(a.vocal()))
            throw new DomainException("INVALID_VOCAL", "人声仅支持 auto / male / female。");
    }

    private void validateGenerateLyrics(AgentTool.GenerateLyricsArgs a) {
        requireText("text", a.text());
        requireLanguage(a.language());
        requireMode(a.mode(), Set.of("new", "rewrite", "regenerate"));
        if ("rewrite".equals(a.mode()) && a.text() != null && a.text().length() > 900)
            throw new DomainException("INVALID_TOOL_ARGUMENT", "改词要求请控制在900字以内。");
    }

    private void validateModifySection(AgentTool.ModifySectionArgs a) {
        if (a.section() == null || a.section().isBlank()) {
            throw new DomainException("INVALID_SECTION", "请指定要修改的段落。");
        }
        requireText("replacementLyrics", a.replacementLyrics());
        if (a.startS() == null || a.endS() == null || !Double.isFinite(a.startS()) || !Double.isFinite(a.endS())
                || a.startS() < 0 || a.endS() <= a.startS())
            throw new DomainException("INVALID_SECTION", "请明确要替换的音频起止秒数，不能自动猜测片段。");
        if (a.replacementLyrics() == null || a.replacementLyrics().isBlank()) {
            throw new DomainException("INVALID_SECTION", "替换歌词不能为空。");
        }
    }

    private void validateExtendSong(AgentTool.ExtendSongArgs a) {
        if (a.direction() != null && !Set.of("forward", "backward").contains(a.direction())) {
            throw new DomainException("INVALID_EXTEND_DIRECTION", "延长方向仅支持 forward / backward。");
        }
        if (a.seconds() != null && (a.seconds() <= 0 || a.seconds() > 600)) {
            throw new DomainException("INVALID_EXTEND_SECONDS", "延长秒数需在 1–600 之间。");
        }
    }

    private void validateSetStyle(AgentTool.SetStyleArgs a) {
        requireStyle(a.style());
        requireLanguage(a.language());
        requireBpm(a.bpm());
        requireText("mood", a.mood());
        if (a.vocal() != null && !Set.of("AUTO", "MALE", "FEMALE", "auto", "male", "female").contains(a.vocal()))
            throw new DomainException("INVALID_VOCAL", "人声仅支持 auto / male / female。");
        requireInstruments(a.instruments());
    }

    private void requireMode(String mode, Set<String> allowed) {
        if (mode != null && !mode.isBlank() && !allowed.contains(mode))
            throw new DomainException("INVALID_TOOL_ARGUMENT", "不支持的创作模式。");
    }

    private void requireInstruments(java.util.List<String> instruments) {
        if (instruments != null && (instruments.size() > 8 || instruments.stream().anyMatch(s -> s == null || s.isBlank() || s.length() > 30)))
            throw new DomainException("INVALID_TOOL_ARGUMENT", "最多指定8种乐器，每种名称不超过30字。");
    }
}
