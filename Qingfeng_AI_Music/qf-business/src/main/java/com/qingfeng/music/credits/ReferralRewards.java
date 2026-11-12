package com.qingfeng.music.credits;

public interface ReferralRewards {
    void rewardForFirstLogin(String creator);
    default String code(String creator) { throw new UnsupportedOperationException("Referral lookup unavailable"); }
    default void accept(String invitee, String code) { throw new UnsupportedOperationException("Referral acceptance unavailable"); }
}
