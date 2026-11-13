package com.qingfeng.music.server.config;

import com.qingfeng.music.infrastructure.persistence.usage.JdbcUsageBudget;
import com.qingfeng.music.infrastructure.persistence.usage.JdbcUsageBudget.Limits;
import com.qingfeng.music.usage.UsageBudget.Kind;
import java.time.*;
import java.util.Map;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods=false) @Profile({"mysql","test"})
public class UsageConfiguration {
    @org.springframework.beans.factory.annotation.Value("${qingfeng.credits.writer-fence.enabled:false}") private boolean fenced;
    @org.springframework.beans.factory.annotation.Value("${qingfeng.credits.writer-fence.expected-database:}") private String expectedDatabase;
    @org.springframework.beans.factory.annotation.Value("${qingfeng.credits.writer-fence.expected-port:0}") private int expectedPort;
    @Bean com.qingfeng.music.credits.CreditsLedger creditsLedger(JdbcTemplate jdbc, PlatformTransactionManager manager,Environment environment) {
        var transactions=new TransactionTemplate(manager);
        var base=new com.qingfeng.music.infrastructure.persistence.credits.JdbcCreditsLedger(jdbc,transactions);
        if(!fenced){
            if(CreditSchemaReadiness.installed(jdbc))throw new IllegalStateException("Entitlement schema installed: a legacy credit writer cannot start");
            String product=jdbc.execute((ConnectionCallback<String>)connection->connection.getMetaData().getDatabaseProductName());
            if("MySQL".equals(product))CreditSchemaReadiness.requireCore43Ready(jdbc);
            else if(!"H2".equals(product)||!environment.acceptsProfiles(Profiles.of("test"))||environment.acceptsProfiles(Profiles.of("mysql")))
                throw new IllegalStateException("Only verified MySQL core43 or explicit H2 test compatibility may use the legacy writer");
            return base;
        }
        var identity=jdbc.queryForMap("SELECT DATABASE() database_name,@@port database_port");
        if(expectedDatabase==null||expectedDatabase.isBlank()||expectedPort<1||!expectedDatabase.equals(identity.get("database_name"))
                ||expectedPort!=((Number)identity.get("database_port")).intValue())
            throw new IllegalStateException("credit entitlement writer requires an explicitly matched database identity");
        CreditSchemaReadiness.requireActive(jdbc);
        return new com.qingfeng.music.infrastructure.persistence.credits.JdbcFencedCreditsLedger(base,jdbc,transactions);
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
