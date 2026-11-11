package com.qingfeng.music.agent.api;

/** LLM 意图分类端口。W1 默认关闭，实现只做结构化输出，不接触任何音乐生成能力。 */
public interface IntentClassifier {

    boolean isConfigured();

    /** 返回 null 表示「无法给出可信分类」，由 IntentRouter 降级为规则澄清。 */
    IntentResult classify(String text, String locale);
}
