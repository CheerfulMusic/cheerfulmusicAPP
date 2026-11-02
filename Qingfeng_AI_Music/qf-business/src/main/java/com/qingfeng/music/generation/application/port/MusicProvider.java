package com.qingfeng.music.generation.application.port;

import com.qingfeng.music.generation.domain.GenerationStatus;

/** 供应商适配实现位于 infrastructure；业务不依赖供应商DTO。 */
public interface MusicProvider {
    String name();
    boolean isConfigured();
    Submission submit(Request request, String idempotencyKey);
    Result query(String providerTaskId);
    default void validate(Request request) { }

    /** 只有供应商明确拒绝且确定未受理才可使用；超时/断连必须视为提交结果未知。 */
    class Rejected extends RuntimeException { public Rejected() { super("供应商明确拒绝请求"); } }

    record Request(String title, String confirmedLyrics, String style) {}
    record Submission(String providerTaskId) {}
    record Result(String providerTaskId, GenerationStatus status, String temporaryAudioUrl) {}
}
