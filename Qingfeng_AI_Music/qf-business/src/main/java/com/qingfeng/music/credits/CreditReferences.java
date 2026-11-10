package com.qingfeng.music.credits;

import java.util.Locale;

/**
 * 积分账本引用键：同一任务同一请求键只能有一条扣费与一条退款。
 * 引用键同时承担幂等职责，重复提交不会重复扣费。
 */
public final class CreditReferences {
    private CreditReferences() {}
    public static String of(String kind, String work, String requestKey) {
        return kind.toLowerCase(Locale.ROOT) + ":" + work + ":" + requestKey;
    }
}
