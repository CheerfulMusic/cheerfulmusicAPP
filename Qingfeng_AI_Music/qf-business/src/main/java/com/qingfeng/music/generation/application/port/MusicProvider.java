package com.qingfeng.music.generation.application.port;

import com.qingfeng.music.generation.domain.GenerationStatus;

/** 供应商适配实现位于 infrastructure；业务不依赖供应商DTO。 */
public interface MusicProvider {
    String name();
    boolean isConfigured();
    Submission submit(Request request, String idempotencyKey);
    Result query(String providerTaskId);
    default void validate(Request request) { }
    default boolean supportsSectionEditing(){return false;}
    default Submission replace(Request request,Replacement replacement,String requestKey){throw new Rejected();}
    /** 只读补齐旧歌曲标识，必须与已归档的音频地址匹配。 */
    default AudioInfo sourceInfo(String task,String archivedUrl){return null;}
    record Replacement(String taskId,String audioId,double start,double end,String lyrics){}
    record AudioInfo(String audioId,double duration,String audioUrl){}

    /** 只有供应商明确拒绝且确定未受理才可使用；超时/断连必须视为提交结果未知。 */
    class Rejected extends RuntimeException { public Rejected() { super("供应商明确拒绝请求"); } }

    record Request(String title, String confirmedLyrics, String style,String vocalGender,String negativeTags,boolean preparedStyle) {
        public Request{negativeTags=negativeTags==null?"":negativeTags;vocalGender=com.qingfeng.music.work.api.VocalGender.normalize(vocalGender);}
        public Request(String title,String confirmedLyrics,String style,String vocalGender){this(title,confirmedLyrics,style,vocalGender,"",false);}
        public Request(String title,String confirmedLyrics,String style){this(title,confirmedLyrics,style,"AUTO");}
    }
    record Submission(String providerTaskId) {}
    record Result(String providerTaskId, GenerationStatus status, String temporaryAudioUrl,AudioInfo audioInfo) {
        public Result(String task,GenerationStatus status,String url){this(task,status,url,null);}
    }
}
