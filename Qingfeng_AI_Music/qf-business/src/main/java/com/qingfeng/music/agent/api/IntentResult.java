package com.qingfeng.music.agent.api;

import java.util.Map;

/**
 * 两级意图路由的输出契约，W1 冻结。规则层直接产出 RULE；LLM 兜底产出 LLM；
 * 开放输入且 LLM 关闭/失败时，由规则层产出 needClarify=true 的澄清意图，绝不猜测工具。
 */
public record IntentResult(
        String tool,
        Map<String, Object> args,
        double confidence,
        boolean needClarify,
        String replyText,
        Source source) {

    public enum Source { RULE, LLM }

    public IntentResult {
        if (Double.isNaN(confidence) || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("confidence must be within [0,1]");
        }
        if (replyText == null) throw new IllegalArgumentException("replyText is required");
        args = args == null ? Map.of() : Map.copyOf(args);
    }

    /** 无法判定意图时的统一兜底：不给工具、不触发任何付费调用。 */
    public static IntentResult clarify(String replyText) {
        return new IntentResult(null, Map.of(), 0.0, true, replyText, Source.RULE);
    }

    public static IntentResult rule(String tool, Map<String, Object> args, String replyText) {
        return new IntentResult(tool, args, 1.0, false, replyText, Source.RULE);
    }
}
