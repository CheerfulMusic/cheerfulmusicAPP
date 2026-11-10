package com.qingfeng.music.agent.api;

import java.util.List;

/**
 * 参考音乐语义化端口：只接收浏览器端算出的客观特征与用户文字，永远不接收音频字节。
 * 实现方负责与模型通信；风格白名单、BPM 采信与长度约束由应用层统一裁决。
 */
public interface ReferenceAnalyst {
    boolean isConfigured();

    Suggestion analyse(Request request);

    /** 客观测量值。比例均为 0–1；BPM 由浏览器算法给出，可能不可信。 */
    record Features(double bpm, double bpmConfidence, double rms, double peak, double dynamicRange,
                    double low, double mid, double high, double brightness, double noisiness,
                    double duration, double analyzedSeconds) {}

    record Request(String text, String fileName, double duration, String locale, Features features) {}

    /** 模型原始输出；字段可能缺失、越界或类型不符，由应用层校验后才会对外。 */
    record Suggestion(String style, String mood, double tempo, List<String> instrumentation,
                      List<String> negativeTags, String summary) {}
}
