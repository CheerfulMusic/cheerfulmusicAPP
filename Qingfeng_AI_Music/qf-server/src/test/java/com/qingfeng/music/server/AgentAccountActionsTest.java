package com.qingfeng.music.server;

import com.qingfeng.music.credits.*;
import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.infrastructure.persistence.usage.JdbcUsageBudget;
import com.qingfeng.music.server.agent.AgentAccountActions;
import com.qingfeng.music.server.analytics.AnalyticsDataErasureService;
import com.qingfeng.music.server.payment.PaymentService;
import com.qingfeng.music.server.payment.PayPalPaymentService;
import com.qingfeng.music.server.security.CreatorPrincipal;
import com.qingfeng.music.common.exception.DomainException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentAccountActionsTest {
    final CreditsLedger credits=mock(CreditsLedger.class);
    final CreditsPricing pricing=mock(CreditsPricing.class);
    final RewriteCharges rewrites=mock(RewriteCharges.class);
    final JdbcUsageBudget usage=mock(JdbcUsageBudget.class);
    final PaymentService payments=mock(PaymentService.class);
    final PayPalPaymentService paypal=mock(PayPalPaymentService.class);
    final IdentityApi identity=mock(IdentityApi.class);
    final ReferralRewards referrals=mock(ReferralRewards.class);
    final AnalyticsDataErasureService erasure=mock(AnalyticsDataErasureService.class);
    final JdbcTemplate db=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:agent-account-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa",""));
    final AgentAccountActions actions=new AgentAccountActions(credits,pricing,rewrites,usage,payments,paypal,identity,referrals,erasure,db);
    @Test void pricesAndBalanceAreReadFromCurrentServices(){
        when(credits.balance("owner")).thenReturn(945L);
        when(pricing.cost(CreditsPricing.Kind.MUSIC)).thenReturn(37L);
        when(pricing.cost(CreditsPricing.Kind.LYRICS)).thenReturn(3L);
        when(pricing.cost(CreditsPricing.Kind.REWRITE)).thenReturn(2L);
        var result=run("get_credits",Map.of());
        assertEquals("945 积分",result.items().get(0).subtitle());
        assertEquals("37 积分 / 次",result.items().get(1).subtitle());
        verify(credits,never()).credit(anyString(),anyLong(),anyString());
        verify(credits,never()).debit(anyString(),anyLong(),anyString());
    }
    @Test void creditHistoryIsStrictlyOwnerScopedWithoutExposingInternalReferences(){
        db.execute("CREATE TABLE credit_ledger(owner_id VARCHAR(36),entry_type VARCHAR(20),amount BIGINT,reference_id VARCHAR(80),created_at TIMESTAMP)");
        db.update("INSERT INTO credit_ledger VALUES('owner','DEBIT',-5,'private-job-reference',CURRENT_TIMESTAMP)");
        db.update("INSERT INTO credit_ledger VALUES('someone-else','CREDIT',1000,'secret-reference',CURRENT_TIMESTAMP)");
        var result=run("list_credit_entries",Map.of("owner","someone-else"));
        assertEquals(1,result.items().size());assertTrue(result.items().get(0).subtitle().startsWith("-5"));
        assertFalse(result.toString().contains("private-job-reference"));assertFalse(result.toString().contains("secret-reference"));
    }
    @Test void loginRechargeAndRecoveryOnlyReturnNativeCommands(){
        assertEquals("login",run("login",Map.of()).commands().get(0).kind());
        assertEquals("recharge",run("recharge",Map.of()).commands().get(0).kind());
        assertEquals("account",run("account_settings",Map.of()).commands().get(0).kind());
        verifyNoInteractions(payments,paypal,identity,credits);
    }
    @Test void orderQueriesUseAuthenticatedOwnerAndNeverCaptureOrCreatePayments(){
        when(paypal.order("owner","order-one")).thenReturn(Map.of("orderId","order-one","status","PENDING","credits",100));
        var result=run("get_order",Map.of("orderId","order-one","provider","paypal","creatorId","someone-else"));
        assertTrue(result.items().get(0).subtitle().contains("PENDING"));
        verify(paypal).order("owner","order-one");verify(paypal,never()).capture(anyString(),anyString());verify(paypal,never()).create(anyString(),anyString());
        verifyNoInteractions(credits,payments);
    }
    @Test void bothPaymentChannelsAreListedAndDisabledPackagesAreNotOffered(){
        when(payments.orders(new CreatorPrincipal("owner"))).thenReturn(Map.of("orders",List.of(Map.of("orderId","a","status","PAID","credits",100,"createdAt","2026-01-01"))));
        when(paypal.orders("owner")).thenReturn(Map.of("orders",List.of(Map.of("orderId","p","status","PENDING","credits",300,"createdAt","2026-02-01"))));
        var orders=run("list_orders",Map.of());assertEquals("p",orders.items().get(0).id());assertEquals(2,orders.items().size());
        when(payments.catalog()).thenReturn(Map.of("enabled",false,"packages",List.of(Map.of("id","hidden"))));
        when(paypal.catalog()).thenReturn(Map.of("enabled",true,"packages",List.of(Map.of("id","standard","title","300 Credits","amount","25.90","currency","USD","credits",300))));
        var catalog=run("payment_catalog",Map.of());assertEquals(1,catalog.items().size());assertEquals("paypal:standard",catalog.items().get(0).id());
    }
    @Test void referralDelegatesToExistingEligibilityRules(){
        when(referrals.code("owner")).thenReturn("abc");
        assertEquals("/account?invite=abc",run("get_referral",Map.of()).items().get(0).data().get("invitePath"));
        run("accept_referral",Map.of("code","inviter"));verify(referrals).accept("owner","inviter");verify(referrals,never()).rewardForFirstLogin(anyString());verifyNoInteractions(credits);
    }
    @Test void failedErasureIsNotReportedAsSuccess(){
        when(erasure.erase("owner")).thenReturn(new AnalyticsDataErasureService.Result(AnalyticsDataErasureService.Status.DISABLED,"r","",0,0,"off"));
        var result=run("erase_analytics",Map.of("creatorId","someone-else"));assertNull(result.action());assertTrue(result.reply().contains("暂未完成"));verify(erasure).erase("owner");
    }
    @Test void unknownProvidersAndMissingOrderTargetsFailClosed(){
        assertThrows(DomainException.class,()->run("get_order",Map.of("orderId","123","provider","evil")));
        assertThrows(DomainException.class,()->run("get_order",Map.of()));verifyNoInteractions(payments,paypal);
    }
    private com.qingfeng.music.agent.api.AgentApplicationActions.Result run(String action,Map<String,Object> args){return actions.execute("owner","conversation",action,args,"用户明确请求",UUID.randomUUID().toString(),null);}
}
