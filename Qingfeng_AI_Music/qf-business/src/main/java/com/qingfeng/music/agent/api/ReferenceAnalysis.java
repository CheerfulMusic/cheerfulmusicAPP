package com.qingfeng.music.agent.api;

import java.util.List;

/**
 * 对外的参考音乐分析结果。
 * tempo 只在浏览器端 BPM 可信时才给出；inferredInstrumentation 明确标注为 AI 推断，不是音频算法检测。
 */
public record ReferenceAnalysis(String style, String mood, double tempo, boolean tempoVerified,
                                List<String> inferredInstrumentation, List<String> negativeTags,
                                String summary, String source) {
    public static final String SOURCE_AI = "AI";
    public static final String SOURCE_RULE = "RULE";

    public ReferenceAnalysis {
        inferredInstrumentation = List.copyOf(inferredInstrumentation);
        negativeTags = List.copyOf(negativeTags);
    }
}
