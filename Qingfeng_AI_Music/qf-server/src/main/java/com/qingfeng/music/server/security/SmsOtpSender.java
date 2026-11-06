package com.qingfeng.music.server.security;

/** Delivers an already generated Logto OTP. Does not generate or verify codes. */
public interface SmsOtpSender extends AutoCloseable {
    void send(String internationalDigits, String code);
    default void close() {}
}
