package com.qingfeng.music.credits;

/** 金额采用整数积分；reference 必须由可信服务端提供。 */
public interface CreditsLedger {
    void credit(String owner, long amount, String reference);
    void debit(String owner, long amount, String reference);
    void refund(String owner, String debitReference);
    long balance(String owner);
}
