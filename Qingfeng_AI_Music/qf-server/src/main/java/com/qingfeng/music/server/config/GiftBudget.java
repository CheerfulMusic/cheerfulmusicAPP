package com.qingfeng.music.server.config;

import com.qingfeng.music.credits.CreditsLedger;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@Profile({"mysql","test"})
public class GiftBudget {
    private final JdbcTemplate db;
    private final TransactionTemplate transactions;
    private final CreditsLedger ledger;
    private final long welcomeLimit;
    private final long referralLimit;
    private final ZoneId zone;

    public GiftBudget(JdbcTemplate db, TransactionTemplate transactions, CreditsLedger ledger,
                      @Value("${qingfeng.gifts.welcome.daily-credits:-1}") long welcomeLimit,
                      @Value("${qingfeng.gifts.referral.daily-credits:-1}") long referralLimit,
                      @Value("${qingfeng.usage.zone:Asia/Shanghai}") String zone) {
        if (welcomeLimit < -1 || referralLimit < -1) throw new IllegalArgumentException("Invalid daily gift budget");
        this.db = db;
        this.transactions = transactions;
        this.ledger = ledger;
        this.welcomeLimit = welcomeLimit;
        this.referralLimit = referralLimit;
        this.zone = ZoneId.of(zone);
    }

    public void welcome(String owner, long amount, String reference) {
        grant("WELCOME", welcomeLimit, owner, amount, reference);
    }

    public void referral(String owner, long amount, String reference) {
        grant("REFERRAL", referralLimit, owner, amount, reference);
    }

    private void grant(String kind, long limit, String owner, long amount, String reference) {
        if (limit == -1) {
            ledger.credit(owner, amount, reference);
            return;
        }
        if (amount <= 0) throw new IllegalArgumentException("Invalid gift amount");
        if (amount > limit) return;
        transactions.executeWithoutResult(tx -> {
            Date day = Date.valueOf(LocalDate.now(zone));
            db.queryForObject("SELECT id FROM creator WHERE id=? FOR UPDATE", String.class, owner);
            db.update("INSERT INTO gift_daily_budget(gift_kind,usage_day,spent) VALUES(?,?,0) ON DUPLICATE KEY UPDATE spent=spent", kind, day);
            if (!db.queryForList("SELECT reference_id FROM credit_ledger WHERE entry_type='CREDIT' AND reference_id=? FOR UPDATE", reference).isEmpty()) return;
            int reserved = db.update("UPDATE gift_daily_budget SET spent=spent+? WHERE gift_kind=? AND usage_day=? AND spent<=?",
                    amount, kind, day, limit - amount);
            if (reserved == 1) ledger.credit(owner, amount, reference);
        });
    }
}
