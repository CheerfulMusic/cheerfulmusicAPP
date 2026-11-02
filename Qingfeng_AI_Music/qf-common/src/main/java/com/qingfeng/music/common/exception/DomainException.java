package com.qingfeng.music.common.exception;

/** 业务异常不携带供应商原始响应或敏感配置。 */
public final class DomainException extends RuntimeException {
    private final String code;
    public DomainException(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
}
