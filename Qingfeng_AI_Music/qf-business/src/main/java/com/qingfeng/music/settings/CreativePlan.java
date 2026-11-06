package com.qingfeng.music.settings;

import com.qingfeng.music.work.api.WorkApi.Work;
import com.qingfeng.music.common.exception.DomainException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 用户明确要求与系统补全分开保存。音乐计划不是歌词，也不能改变已确认的歌词。 */
public record CreativePlan(String requirements, String suggestedArrangement, String negativeTags,
                           String summaryZh, String summaryEn, String vocalGender) {
    public CreativePlan {
        requirements=bounded(requirements,600);suggestedArrangement=bounded(suggestedArrangement,240);
        negativeTags=bounded(negativeTags,250);summaryZh=bounded(summaryZh,200);summaryEn=bounded(summaryEn,300);
        vocalGender=com.qingfeng.music.work.api.VocalGender.normalize(vocalGender);
    }
    private static String bounded(String value,int max){
        String text=value==null?"":value.trim();
        if(text.length()>max)throw new DomainException("INVALID_CREATIVE_PLAN","创作要求整理过长，请重新整理或精简要求。");
        return text;
    }
    /** 风格设置可在写词后改变；故事变化后旧计划必须失效。 */
    public static String sourceHash(Work work){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
            String.join("\n",work.recipient(),work.occasion(),work.story(),work.message()).getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    public interface Repository {
        Optional<CreativePlan> find(String work,String sourceHash);
        void save(String work,String revision,String sourceHash,CreativePlan plan);
    }
}
