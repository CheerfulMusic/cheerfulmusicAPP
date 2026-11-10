package com.qingfeng.music.credits;

/**
 * AI 改写的计费与每日免费额度。
 *
 * 改写发生在作品创建之前，没有作品 ID，因此单独记账，不与歌词、歌曲共用额度预留表：
 * 「每天免费 N 次」是次数型配额，「超出后按积分收费」才是货币型消费。
 *
 * 幂等：同一 (用户, requestKey) 只会预扣一次；重复提交返回既有记录，不会重复扣费。
 */
public interface RewriteCharges {
    /**
     * 预扣结果。
     *
     * @param fresh  true 表示本次是新的预扣；false 表示该 requestKey 已有记录
     * @param free   true 表示落在每日免费额度内，本次不扣积分
     * @param cost   本次实际扣除的积分，免费时为 0
     * @param status 既有记录的状态（RESERVED / SUCCEEDED / FAILED），新预扣时为 RESERVED
     * @param result 既有记录的成功结果，新预扣时为 null
     */
    record Reservation(boolean fresh, boolean free, long cost, String status, String result) {}

    /** 预扣：命中免费额度则不扣积分，否则扣一次积分；同一 requestKey 幂等。 */
    Reservation reserve(String owner, String requestKey);

    /** 改写成功：结算流水并保存结果，供同一 requestKey 幂等重放。 */
    void succeed(String owner, String requestKey, String result);

    /**
     * 改写失败：退还已扣的积分。
     * 免费额度不退还 —— 模型调用已经发生，成本已经产生，退还免费次数会被用来刷免费调用。
     */
    void fail(String owner, String requestKey);

    /** 今日免费次数剩余。 */
    long freeRemaining(String owner);

    /**
     * 不计费实现。
     * 用于本次不纳入计费范围的同源功能（如音乐设置精简），避免误扣积分。
     */
    RewriteCharges FREE = new RewriteCharges() {
        @Override public Reservation reserve(String owner,String requestKey){return new Reservation(true,true,0,"RESERVED",null);}
        @Override public void succeed(String owner,String requestKey,String result){}
        @Override public void fail(String owner,String requestKey){}
        @Override public long freeRemaining(String owner){return Long.MAX_VALUE;}
    };
}
