package com.qingfeng.music.server;

import com.qingfeng.music.credits.CreditsLedger;
import com.qingfeng.music.credits.CreditsPricing;
import com.qingfeng.music.credits.DailyQuota;
import com.qingfeng.music.infrastructure.persistence.credits.JdbcCreditsLedger;
import com.qingfeng.music.infrastructure.persistence.credits.JdbcDailyQuota;
import com.qingfeng.music.infrastructure.persistence.credits.JdbcRewriteCharges;
import java.time.Clock;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 跨进程并发验证入口：用真实 {@link JdbcCreditsLedger}（修复后的锁序）连接同一 MySQL，
 * 由两个独立 JVM 进程并发对同一 owner 做 debit+refund，验证跨进程行锁与账本一致性。
 *
 * 参数：url owner tag iterations mode(seed|run)
 * - seed：确保 creator 存在并注入初始积分（由编排脚本先执行一次）。
 * - run：循环 iterations 次 debit+refund，打印最终余额。
 */
public final class CreditCrossProcessMain {
    public static void main(String[] args) {
        String url = args[0];
        String owner = args[1];
        String tag = args[2];
        int iterations = Integer.parseInt(args[3]);
        String mode = args[4];

        var ds = new DriverManagerDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setUrl(url);
        ds.setUsername("root");
        ds.setPassword("");
        var jdbc = new JdbcTemplate(ds);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        CreditsLedger credits = new JdbcCreditsLedger(jdbc, tx);

        if ("seed".equals(mode)) {
            jdbc.update("INSERT IGNORE INTO creator(id,created_at) VALUES(?,CURRENT_TIMESTAMP(6))", owner);
            credits.credit(owner, 1000, "fund");
            System.out.println("SEEDED owner=" + owner + " balance=" + credits.balance(owner));
            return;
        }
        if ("rewrite-reap".equals(mode)) {
            // 用真实 JdbcRewriteCharges 触发 reapStale（回收崩溃遗留的 RESERVED 记录），验证跨进程回收幂等。
            DailyQuota quota = new JdbcDailyQuota(jdbc, Clock.systemUTC(), ZoneId.of("UTC"));
            CreditsPricing pricing = new CreditsPricing() {
                public boolean enabled() { return true; }
                public long cost(Kind k) { return 1L; }
                public long freePerDay(Kind k) { return 0L; }
            };
            var charges = new JdbcRewriteCharges(jdbc, tx, credits, pricing, quota, 1000, 1000);
            charges.reserve(owner, UUID.randomUUID().toString());
            System.out.println("REAP-DONE owner=" + owner + " tag=" + tag);
            return;
        }
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < iterations; i++) {
            String ref = tag + "-" + i;
            credits.debit(owner, 1, ref);
            credits.refund(owner, ref);
        }
        System.out.println("RESULT tag=" + tag + " balance=" + credits.balance(owner)
            + " ops=" + iterations + " ms=" + (System.currentTimeMillis() - t0));
    }
}
