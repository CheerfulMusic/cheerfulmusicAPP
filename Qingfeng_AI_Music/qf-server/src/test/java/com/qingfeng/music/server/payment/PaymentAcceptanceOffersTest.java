package com.qingfeng.music.server.payment;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class PaymentAcceptanceOffersTest {
    private static final String OWNER="00000000-0000-0000-0000-000000000001";
    private static final Instant NOW=Instant.parse("2026-11-13T00:00:00Z");
    private MockEnvironment settings(){return new MockEnvironment().withProperty("qingfeng.payment.acceptance.enabled","true")
        .withProperty("qingfeng.payment.acceptance.owner-id",OWNER).withProperty("qingfeng.payment.acceptance.nonce","abcdef01")
        .withProperty("qingfeng.payment.acceptance.expires-at","2026-11-13T01:00:00Z");}
    private PaymentAcceptanceOffers offers(MockEnvironment env){return new PaymentAcceptanceOffers(env,Clock.fixed(NOW,ZoneOffset.UTC));}
    @Test void disabledByDefaultAndOnlyOneExactAuthenticatedOwnerCanSeeOffers(){
        assertTrue(offers(new MockEnvironment()).offer(OWNER,"card").isEmpty());
        var offers=offers(settings());assertTrue(offers.offer(null,"card").isEmpty());
        assertTrue(offers.offer("00000000-0000-0000-0000-000000000002","card").isEmpty());
        assertThrows(ResponseStatusException.class,()->offers.select(null,"accept-abcdef01-card-USD","card"));
    }
    @Test void minimumQuotesAreOneCreditAndNormalCatalogPricesRemainUnchanged(){
        var offers=offers(settings());String[] methods={"card","apple_pay","wechat_pay","paypal"},amounts={"0.50","0.50","3.00","0.01"};
        for(int i=0;i<methods.length;i++){
            var pack=offers.offer(OWNER,methods[i]).orElseThrow();assertEquals(new BigDecimal(amounts[i]),pack.amount());assertEquals(1,pack.credits());assertTrue(pack.id().length()<=36);
            var view=offers.view(pack,methods[i]);assertEquals(true,view.get("acceptance"));assertEquals(1,view.get("maxPurchases"));
            assertEquals(!"paypal".equals(methods[i]),view.containsKey("paymentMethod"));
        }
        assertEquals(new BigDecimal("9.90"),new PaymentCatalog(settings()).find("starter-USD").orElseThrow().amount());
    }
    @Test void expiredAtBoundaryHasNoOfferAndCannotSelectButHistoricalRoutingNeedsNoConfiguration(){
        var expired=offers(settings().withProperty("qingfeng.payment.acceptance.expires-at",NOW.toString()));
        assertTrue(expired.offer(OWNER,"card").isEmpty());assertTrue(expired.configuredFor(OWNER));
        assertThrows(ResponseStatusException.class,()->expired.select(OWNER,"accept-abcdef01-card-USD","card"));
        assertEquals("wechat_pay",StripePaymentMethods.checkoutMethodForStoredOffer("accept-abcdef01-wechat_pay-CNY"));
        assertEquals("card",StripePaymentMethods.checkoutMethodForStoredOffer("accept-abcdef01-apple_pay-USD"));
    }
    @Test void missingAmbiguousOrUnsafeConfigurationFailsClosed(){
        for(var env:new MockEnvironment[]{settings().withProperty("qingfeng.payment.acceptance.owner-id",OWNER+","+OWNER),settings().withProperty("qingfeng.payment.acceptance.nonce","../bad"),settings().withProperty("qingfeng.payment.acceptance.expires-at","2026-11-13T01:00:00+00:00"),settings().withProperty("qingfeng.payment.acceptance.wechat-cny","1.00"),settings().withProperty("qingfeng.payment.acceptance.wechat-cny","3.001")})
            assertThrows(IllegalArgumentException.class,()->offers(env));
    }
    @Test void methodCurrencyNonceAndChannelCannotBeRebound(){
        var offers=offers(settings());
        for(String id:new String[]{"accept-abcdef02-card-USD","accept-abcdef01-apple_pay-USD","accept-abcdef01-card-CNY"})assertThrows(ResponseStatusException.class,()->offers.select(OWNER,id,"card"));
        assertThrows(ResponseStatusException.class,()->offers.select(OWNER,"accept-abcdef01-card-USD",null));
        assertThrows(IllegalArgumentException.class,()->StripePaymentMethods.checkoutMethodForStoredOffer("accept-abcdef01-paypal-USD"));
        assertThrows(IllegalArgumentException.class,()->StripePaymentMethods.checkoutMethodForStoredOffer("accept-abcdef01-wechat_pay-USD"));
    }
}
