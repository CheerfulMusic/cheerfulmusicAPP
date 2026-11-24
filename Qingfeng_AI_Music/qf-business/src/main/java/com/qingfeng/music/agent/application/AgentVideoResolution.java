package com.qingfeng.music.agent.application;

import java.util.Locale;
import java.util.regex.Pattern;

/** A resolution button names the exact work and audio for which the system displayed prices. */
public record AgentVideoResolution(String resolution, String workId, String assetId) {
    private static final String ID = "([0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12})";
    private static final Pattern BUTTON = Pattern.compile("(?i)用 (480P|720P|1080P) 为作品 "+ID+" 的音频 "+ID+" 制作完整 MV，先展示分镜和总费用让我确认");

    public static AgentVideoResolution parse(String text) {
        var match = BUTTON.matcher(text.strip());
        return match.matches() ? new AgentVideoResolution(match.group(1).toUpperCase(Locale.ROOT),match.group(2),match.group(3)) : null;
    }

    public String command() {
        return "用 "+resolution+" 为作品 "+workId+" 的音频 "+assetId+" 制作完整 MV，先展示分镜和总费用让我确认";
    }
}
