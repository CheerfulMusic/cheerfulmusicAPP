package com.qingfeng.music.server.config;

import com.qingfeng.music.infrastructure.persistence.usage.JdbcUsageBudget;
import com.qingfeng.music.infrastructure.persistence.usage.JdbcUsageBudget.Limits;
import com.qingfeng.music.usage.UsageBudget.Kind;
import java.time.*;
import java.util.Map;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods=false) @Profile({"mysql","test"})
public class UsageConfiguration {
    @Bean com.qingfeng.music.credits.CreditsLedger creditsLedger(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        return new com.qingfeng.music.infrastructure.persistence.credits.JdbcCreditsLedger(jdbc,new TransactionTemplate(manager));
    }
    @Bean com.qingfeng.music.credits.CreditsPricing creditsPricing(Environment env) {
        return new com.qingfeng.music.infrastructure.persistence.credits.EnvironmentCreditsPricing(env);
    }
    @Bean com.qingfeng.music.credits.CreditConsumptions creditConsumptions(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        return new com.qingfeng.music.infrastructure.persistence.credits.JdbcCreditConsumptions(jdbc,new TransactionTemplate(manager));
    }
    @Bean com.qingfeng.music.credits.DailyQuota dailyQuota(JdbcTemplate jdbc, Environment env) {
        return new com.qingfeng.music.infrastructure.persistence.credits.JdbcDailyQuota(jdbc,java.time.Clock.systemUTC(),ZoneId.of(env.getRequiredProperty("qingfeng.usage.zone")));
    }
    @Bean com.qingfeng.music.credits.RewriteCharges rewriteCharges(JdbcTemplate jdbc, PlatformTransactionManager manager, com.qingfeng.music.credits.CreditsLedger credits, com.qingfeng.music.credits.CreditsPricing pricing, com.qingfeng.music.credits.DailyQuota quota, Environment env) {
        return new com.qingfeng.music.infrastructure.persistence.credits.JdbcRewriteCharges(jdbc,new TransactionTemplate(manager),credits,pricing,quota,
            env.getProperty("qingfeng.inspiration.rewrite.concurrent-global",Integer.class,4),
            env.getProperty("qingfeng.inspiration.rewrite.concurrent-per-creator",Integer.class,1));
    }
    @Bean JdbcUsageBudget usageBudget(JdbcTemplate jdbc, PlatformTransactionManager manager, Environment env, com.qingfeng.music.credits.CreditsLedger credits, com.qingfeng.music.credits.CreditConsumptions consumptions, com.qingfeng.music.credits.CreditsPricing pricing) {
        return new JdbcUsageBudget(jdbc,new TransactionTemplate(manager),Map.of(
            Kind.MUSIC,limits(env,"music"),
            Kind.LYRICS,limits(env,"lyrics"),
            Kind.TIMING,limits(env,"timing"),Kind.COVER,limits(env,"cover")),Clock.systemUTC(),ZoneId.of(env.getRequiredProperty("qingfeng.usage.zone"))).withCredits(credits,consumptions,pricing);
    }
    private Limits limits(Environment env,String kind) {
        String prefix="qingfeng.usage."+kind+".";
        return new Limits(env.getRequiredProperty(prefix+"daily-per-creator",Integer.class),env.getRequiredProperty(prefix+"daily-global",Integer.class),env.getRequiredProperty(prefix+"concurrent-per-creator",Integer.class),env.getRequiredProperty(prefix+"concurrent-global",Integer.class));
    }
}
