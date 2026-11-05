package com.qingfeng.music.lyrics.application;

/** 将创作意图整理为散文描述，不生成歌词，也不直接修改作品。 */
public interface InspirationRewriter {
    boolean isConfigured();
    String rewrite(String text, String language);
}
