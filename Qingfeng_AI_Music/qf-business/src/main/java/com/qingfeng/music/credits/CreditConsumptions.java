package com.qingfeng.music.credits;

/**
 * 消费流水：记录一次创作消费的业务语义（任务、消费类型、任务状态），
 * 与只记录金额增减的积分账本分开存放，便于对账和客服核查。
 */
public interface CreditConsumptions {
    /** 消费类型：歌词生成。其余创作类型的流水口径另行接入，不在本次改动范围内。 */
    String TYPE_LYRICS = "LYRICS_GENERATION";

    /** 任务状态：已预留（已扣费未结算）、已成功、已失败。 */
    String STATUS_RESERVED = "RESERVED";
    String STATUS_SUCCEEDED = "SUCCEEDED";
    String STATUS_FAILED = "FAILED";

    /** 预留即记账；同一 (消费类型, 任务, 请求键) 只记一次，重复调用不会重复写入。 */
    void reserved(String owner, String work, String requestKey, String type, long amount);

    /** 结算任务状态；refunded 表示积分已退还给用户。 */
    void settled(String owner, String work, String requestKey, String type, String status, boolean refunded);
}
