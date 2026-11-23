package com.qingfeng.music.credits;

/** One welcome grant per creator, shared by all verified login providers. */
public final class FirstLoginCredits {
    public static final long AMOUNT = 19;
    @FunctionalInterface
    public interface Award { void grant(String owner, long amount, String reference); }
    private final Award award;
    private final ReferralRewards referrals;

    public FirstLoginCredits(CreditsLedger credits, ReferralRewards referrals) {
        this(credits::credit, referrals);
    }

    public FirstLoginCredits(Award award, ReferralRewards referrals) {
        this.award = award;
        this.referrals = referrals;
    }

    /** Call only when the creator binds their first verified identity, in the login transaction. */
    public void grant(String creator) {
        award.grant(creator, AMOUNT, "first-login:" + creator);
        referrals.rewardForFirstLogin(creator);
    }
}
