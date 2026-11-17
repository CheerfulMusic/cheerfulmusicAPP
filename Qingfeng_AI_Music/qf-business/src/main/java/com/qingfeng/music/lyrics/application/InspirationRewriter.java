package com.qingfeng.music.lyrics.application;

import com.qingfeng.music.common.exception.DomainException;
import java.util.List;

/** 提供文本和歌词改写结果，不直接修改作品。 */
public interface InspirationRewriter {
    boolean isConfigured();
    String rewrite(String text, String language);

    record Replacement(int index, String text) {}

    default List<Replacement> rewriteSelection(String text, String language, List<Integer> targetIndices) {
        throw new DomainException("REWRITE_PROVIDER_UNAVAILABLE", "局部歌词改写暂不可用，请稍后再试。");
    }
}
