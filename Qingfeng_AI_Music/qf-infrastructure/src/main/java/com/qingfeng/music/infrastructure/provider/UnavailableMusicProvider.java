package com.qingfeng.music.infrastructure.provider;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.generation.application.port.MusicProvider;

/** 初始化阶段显式不可用，不使用伪造任务或示例音频冒充生成结果。 */
public final class UnavailableMusicProvider implements MusicProvider {
    public String name() { return "unconfigured"; }
    public boolean isConfigured() { return false; }
    public Submission submit(Request request, String idempotencyKey) { throw unavailable(); }
    public Result query(String providerTaskId) { throw unavailable(); }
    private DomainException unavailable() { return new DomainException("MUSIC_PROVIDER_UNAVAILABLE", "音乐生成服务尚未配置"); }
}
