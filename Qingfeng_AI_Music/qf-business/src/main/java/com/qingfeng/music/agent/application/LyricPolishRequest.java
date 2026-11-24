package com.qingfeng.music.agent.application;

import java.util.regex.Pattern;

/** A standalone instruction to turn an idea into lyrics or polish a selected revision. */
public enum LyricPolishRequest {
    FROM_CONTEXT, SELECTED_VERSION;

    private static final String SELECTED = "(?:当前|现有|选中|这一?(?:版|段|首(?:歌)?))(?:的)?";
    private static final String LYRICS = "(?:" + SELECTED + "|一版)?歌词";
    private static final String VERB = "(?:打磨|润色)";
    private static final String NO_AUDIO = "(?:先别|先不|不要|暂不)生成(?:音乐|歌曲|音频)";
    private static final String SEPARATOR = "\\h*[，,。]\\h*";
    private static final String CHINESE = "(?:" + NO_AUDIO + SEPARATOR + ")?"
            + "(?:请|麻烦)?你?(?:先|再)?(?:帮我|给我)?(?:先|再)?"
            + "(?:" + VERB + "(?:一下)?" + LYRICS + "|(?:把)?" + LYRICS + "(?:先|再)?" + VERB + "(?:一下|一版)?)吧?"
            + "(?:" + SEPARATOR + NO_AUDIO + ")?";
    private static final String ENGLISH = "(?:please\\h+)?(?:polish|refine)\\h+(?:(?:the|my|these)\\h+)?"
            + "(?:(?:current|selected|existing)\\h+)?lyrics(?:\\h+first)?";
    private static final Pattern COMMAND = Pattern.compile("(?:" + CHINESE + "|" + ENGLISH + ")[。.!！]?", Pattern.CASE_INSENSITIVE);
    private static final Pattern REFERENCE = Pattern.compile(SELECTED + "歌词|\\b(?:my|these|current|selected|existing)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern BRIEF = Pattern.compile(
            "(?:帮我构思一首歌[，,]\\h*先聊聊这个灵感[。.]|Help me brainstorm song ideas around this\\."
            + "|(?:这首歌的|歌词的)?主题(?:是|[：:])|(?:my |the )?song theme is\\b)\\s*(.+)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    public static LyricPolishRequest parse(String text) {
        if (text == null || !COMMAND.matcher(text.strip()).matches()) return null;
        return REFERENCE.matcher(text).find() ? SELECTED_VERSION : FROM_CONTEXT;
    }

    public static String topicFromBrief(String text) {
        var brief = BRIEF.matcher(text.strip());
        return brief.matches() ? brief.group(1).strip() : "";
    }
}
