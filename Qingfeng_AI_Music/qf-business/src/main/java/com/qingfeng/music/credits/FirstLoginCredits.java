package com.qingfeng.music.credits;

/** One welcome grant per creator, shared by all verified login providers. */
public final class FirstLoginCredits {
    public static final long AMOUNT = 100;
    private final CreditsLedger credits;

    public FirstLoginCredits(CreditsLedger credits) {
        this.credits = credits;
    }

    /** Call only when the creator binds their first verified identity, in the login transaction. */
    public void grant(String creator) {
        credits.credit(creator, AMOUNT, "first-login:" + creator);
    }
}
