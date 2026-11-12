package com.qingfeng.music.server;

import com.qingfeng.music.credits.*;
import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.infrastructure.persistence.usage.JdbcUsageBudget;
import com.qingfeng.music.server.agent.AgentAccountActions;
import com.qingfeng.music.server.analytics.AnalyticsDataErasureService;
import com.qingfeng.music.server.payment.*;
import com.qingfeng.music.server.security.CreatorPrincipal;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Read-only Agent adapter contract; supplier services are mocks and no funds operations run. */
class AgentStripeAccountActionsTest {
    final CreditsLedger credits=mock(CreditsLedger.class);
    final CreditsPricing pricing=mock(CreditsPricing.class);
    final RewriteCharges rewrites=mock(RewriteCharges.class);
    final JdbcUsageBudget usage=mock(JdbcUsageBudget.class);
    final PaymentService payments=mock(PaymentService.class);
    final PayPalPaymentService paypal=mock(PayPalPaymentService.class);
    final StripePaymentService stripe=mock(StripePaymentService.class);
    final IdentityApi identity=mock(IdentityApi.class);
    final ReferralRewards referrals=mock(ReferralRewards.class);
    final AnalyticsDataErasureService erasure=mock(AnalyticsDataErasureService.class);
    final JdbcTemplate db=mock(JdbcTemplate.class);
    AgentAccountActions actions;
    @BeforeEach void fixture(){actions=new AgentAccountActions(credits,pricing,rewrites,usage,payments,paypal,identity,referrals,erasure,db,stripe);}
    com.qingfeng.music.agent.api.AgentApplicationActions.Result run(String action,Map<String,Object> args){return actions.execute("owner","conversation",action,args,"明确用户请求",UUID.randomUUID().toString(),null);}
    Map<String,Object> order(String id,String status,String created){return Map.of("orderId",id,"status",status,"credits",100,"createdAt",created);}
    @Test void allThreeOrderHistoriesUseTheAuthenticatedOwnerAndRetainExactProviders(){
        when(payments.orders(new CreatorPrincipal("owner"))).thenReturn(Map.of("orders",List.of(order("a","PAID","2026-01-01"))));
        when(paypal.orders("owner")).thenReturn(Map.of("orders",List.of(order("p","PENDING","2026-02-01"))));
        when(stripe.orders(new CreatorPrincipal("owner"))).thenReturn(Map.of("orders",List.of(order("s","PAID","2026-03-01"))));
        var result=run("list_orders",Map.of("owner","someone-else","environment","live"));assertEquals(3,result.items().size());assertEquals("s",result.items().get(0).id());
        assertEquals("stripe",result.items().get(0).data().get("provider"));assertEquals(Set.of("airwallex","paypal","stripe"),result.items().stream().map(i->i.data().get("provider")).collect(java.util.stream.Collectors.toSet()));
        assertTrue(result.commands().stream().anyMatch(c->c.text().equals("查询订单 s，渠道 stripe")));
        verify(stripe).orders(new CreatorPrincipal("owner"));verify(payments).orders(new CreatorPrincipal("owner"));verify(paypal).orders("owner");
        verifyNoInteractions(credits,db);
    }
    @Test void stripeQueryUsesOnlyStripeAndNeverStartsAnyPayment(){
        when(stripe.order(new CreatorPrincipal("owner"),"s")).thenReturn(order("s","PAID","2026-03-01"));
        var result=run("get_order",Map.of("orderId","s","provider","stripe","creatorId","someone-else","environment","live"));
        assertEquals("stripe",result.items().get(0).data().get("provider"));assertTrue(result.items().get(0).subtitle().contains("PAID"));
        verify(stripe).order(new CreatorPrincipal("owner"),"s");verify(stripe,never()).create(any(),anyString());verify(stripe,never()).resume(any(),anyString());
        verifyNoInteractions(payments,paypal,credits,db);
    }
    @Test void foreignOrOtherModeStripeOrderCannotFallBackToPayPalOrAirwallex(){
        when(stripe.order(new CreatorPrincipal("owner"),"not-owned")).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND,"not owned"));
        var error=assertThrows(DomainException.class,()->run("get_order",Map.of("orderId","not-owned","provider","stripe")));assertEquals("AGENT_ACCOUNT_ACTION_FAILED",error.code());
        verifyNoInteractions(paypal,payments,credits,db);verify(stripe,never()).create(any(),anyString());
    }
    @Test void legacyPayPalDefaultAndAirwallexRouteArePreservedExactly(){
        when(paypal.order("owner","p")).thenReturn(order("p","PENDING","2026-01-01"));
        when(payments.order(new CreatorPrincipal("owner"),"a")).thenReturn(order("a","PAID","2026-01-01"));
        assertEquals("paypal",run("get_order",Map.of("orderId","p")).items().get(0).data().get("provider"));
        assertEquals("airwallex",run("get_order",Map.of("orderId","a","provider","airwallex")).items().get(0).data().get("provider"));
        verify(paypal).order("owner","p");verify(payments).order(new CreatorPrincipal("owner"),"a");verifyNoInteractions(stripe,credits,db);
    }
    @Test void closeStillOnlySupportsPayPalAndRejectsStripeBeforeAnyServiceCall(){
        for(String provider:List.of("stripe","airwallex","STRIPE_LIVE","evil"))assertThrows(DomainException.class,()->run("close_order",Map.of("orderId","s","provider",provider)));
        verifyNoInteractions(payments,paypal,stripe,credits,db);
        when(paypal.close("owner","p")).thenReturn(order("p","CANCELLED","2026-01-01"));assertTrue(run("close_order",Map.of("orderId","p","provider","paypal")).items().get(0).subtitle().contains("CANCELLED"));verify(paypal).close("owner","p");
    }
    @Test void stripeCatalogUsesTheExistingUsdSnapshotAndDisabledChannelsStayHidden(){
        when(payments.catalog()).thenReturn(Map.of("enabled",false,"packages",List.of(Map.of("id","hidden-airwallex"))));
        when(paypal.catalog()).thenReturn(Map.of("enabled",false,"packages",List.of(Map.of("id","hidden-paypal"))));
        var pack=Map.<String,Object>of("id","starter-USD","title","100 credits","credits",100,"amount","9.90","currency","USD");
        when(stripe.catalog()).thenReturn(Map.of("enabled",true,"packages",List.of(pack)));
        var result=run("payment_catalog",Map.of("currency","GBP"));assertEquals(1,result.items().size());assertEquals("stripe:starter-USD",result.items().get(0).id());assertTrue(result.items().get(0).subtitle().contains("9.90 USD"));
        when(stripe.catalog()).thenReturn(Map.of("enabled",false,"packages",List.of(pack)));assertTrue(run("payment_catalog",Map.of()).items().isEmpty());verify(stripe,never()).create(any(),anyString());
    }
    @Test void nativeRechargeMerelyOpensTheExistingUiAndCapabilityListsStripe(){
        assertEquals("recharge",run("recharge",Map.of("packageId","starter-USD")).commands().get(0).kind());
        assertTrue(actions.capabilities().stream().filter(c->c.id().equals("get_order")).findFirst().orElseThrow().parameters().contains("stripe"));
        verifyNoInteractions(payments,paypal,stripe,credits,db);
    }
    @Test void invalidProviderAndMissingOrderNeverQueryAnyChannel(){
        for(String provider:List.of("Stripe","STRIPE_SANDBOX","evil"))assertThrows(DomainException.class,()->run("get_order",Map.of("orderId","s","provider",provider)));
        assertThrows(DomainException.class,()->run("get_order",Map.of("provider","stripe")));verifyNoInteractions(payments,paypal,stripe,credits,db);
    }
}
