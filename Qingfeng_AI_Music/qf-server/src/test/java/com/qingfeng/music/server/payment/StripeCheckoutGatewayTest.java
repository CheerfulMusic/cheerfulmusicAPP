package com.qingfeng.music.server.payment;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Offline verification of the real Stripe SDK signature boundary. No HTTP or database. */
class StripeCheckoutGatewayTest {
    private static final String ACCOUNT="acct_offline12345678", SECRET="whsec_offline_fixture_not_a_real_credential";

    private StripeCheckoutGateway gateway(String mode,String apiKey) {
        var gateway=new StripeCheckoutGateway(new ObjectMapper());
        ReflectionTestUtils.setField(gateway,"environment",mode);
        ReflectionTestUtils.setField(gateway,"apiKey",apiKey);
        ReflectionTestUtils.setField(gateway,"accountId",ACCOUNT);
        ReflectionTestUtils.setField(gateway,"webhookSecret",SECRET);
        ReflectionTestUtils.setField(gateway,"successUrl","");
        ReflectionTestUtils.setField(gateway,"cancelUrl","");
        return gateway;
    }
    private String event(boolean live,String account) {
        return "{\"id\":\"evt_offline12345678\",\"object\":\"event\",\"api_version\":\"2025-03-31.basil\",\"type\":\"checkout.session.completed\",\"livemode\":"+live+
            (account==null?"":",\"account\":\""+account+"\"")+",\"data\":{\"object\":{\"id\":\"cs_offline12345678\",\"object\":\"checkout.session\"}}}";
    }
    static String signature(String payload,String secret,long timestamp) throws Exception {
        var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        return "t="+timestamp+",v1="+HexFormat.of().formatHex(mac.doFinal((timestamp+"."+payload).getBytes(StandardCharsets.UTF_8)));
    }
    private ResponseStatusException rejected(StripeCheckoutGateway gateway,String payload,String signature) {
        return assertThrows(ResponseStatusException.class,()->gateway.verifyWebhook(payload.getBytes(StandardCharsets.UTF_8),signature));
    }

    @Test void testAndLiveKeysCannotBeMixedOrReplacedByPlaceholders() {
        assertTrue(gateway("sandbox","sk_test_offline_fixture").configured());
        assertTrue(gateway("sandbox","rk_test_offline_fixture").configured());
        assertTrue(gateway("live","rk_live_offline_fixture").configured());
        assertFalse(gateway("sandbox","sk_live_offline_fixture").configured());
        assertFalse(gateway("live","sk_test_offline_fixture").configured());
        assertFalse(gateway("sandbox","${STRIPE_SECRET_KEY}").configured());
        assertFalse(gateway("unknown","sk_test_offline_fixture").configured());
    }
    @Test void merchantAndSigningSecretAreRequiredBeforeAnyRemoteOperation() {
        var gateway=gateway("sandbox","sk_test_offline_fixture");
        ReflectionTestUtils.setField(gateway,"accountId","");assertFalse(gateway.configured());
        assertEquals(503,assertThrows(ResponseStatusException.class,gateway::verifyAccount).getStatusCode().value());
        ReflectionTestUtils.setField(gateway,"accountId",ACCOUNT);
        ReflectionTestUtils.setField(gateway,"webhookSecret","");assertFalse(gateway.configured());
    }
    @Test void officialSdkAcceptsAuthenticRawPayloadAndRejectsAnyBodyMutation() throws Exception {
        var gateway=gateway("sandbox","sk_test_offline_fixture");String body=event(false,null);
        String signature=signature(body,SECRET,Instant.now().getEpochSecond());
        assertEquals("evt_offline12345678",gateway.verifyWebhook(body.getBytes(StandardCharsets.UTF_8),signature).path("id").asString());
        assertEquals(401,rejected(gateway,body+" ",signature).getStatusCode().value());
        assertEquals(401,rejected(gateway,body,signature(body,"whsec_different_fixture",Instant.now().getEpochSecond())).getStatusCode().value());
    }
    @Test void replayOutsideTimestampToleranceIsRejectedEvenWithValidHmac() throws Exception {
        var gateway=gateway("sandbox","sk_test_offline_fixture");String body=event(false,null);
        assertEquals(401,rejected(gateway,body,signature(body,SECRET,Instant.now().minusSeconds(600).getEpochSecond())).getStatusCode().value());
    }
    @Test void signedWrongEnvironmentOrConnectedAccountCannotReachSettlement() throws Exception {
        var gateway=gateway("sandbox","sk_test_offline_fixture");long now=Instant.now().getEpochSecond();
        String wrongMode=event(true,null),wrongAccount=event(false,"acct_other12345678");
        assertEquals(409,rejected(gateway,wrongMode,signature(wrongMode,SECRET,now)).getStatusCode().value());
        assertEquals(409,rejected(gateway,wrongAccount,signature(wrongAccount,SECRET,now)).getStatusCode().value());
        String ownAccount=event(false,ACCOUNT);
        assertEquals(ACCOUNT,gateway.verifyWebhook(ownAccount.getBytes(StandardCharsets.UTF_8),signature(ownAccount,SECRET,now)).path("account").asString());
    }
    @Test void missingMalformedAndOversizedWebhookRequestsAreRejected() throws Exception {
        var gateway=gateway("sandbox","sk_test_offline_fixture");String body=event(false,null);
        assertEquals(400,rejected(gateway,body,null).getStatusCode().value());
        assertEquals(400,rejected(gateway,"","t=1,v1=bad").getStatusCode().value());
        assertEquals(400,rejected(gateway,"x".repeat(1_000_001),"t=1,v1=bad").getStatusCode().value());
        assertEquals(401,rejected(gateway,body,"not-a-stripe-signature").getStatusCode().value());
        String invalidJson="{invalid";
        assertEquals(400,rejected(gateway,invalidJson,signature(invalidJson,SECRET,Instant.now().getEpochSecond())).getStatusCode().value());
    }
    @Test void malformedEventIdentityIsRejectedAfterSuccessfulCryptographicVerification() throws Exception {
        var gateway=gateway("sandbox","sk_test_offline_fixture");String body=event(false,null).replace("evt_offline12345678","untrusted-id");
        assertEquals(409,rejected(gateway,body,signature(body,SECRET,Instant.now().getEpochSecond())).getStatusCode().value());
    }
    @Test void returnUrlsCannotExfiltrateCheckoutIdentityToOtherDomains() {
        var gateway=gateway("live","sk_live_offline_fixture");
        for(String url:new String[]{"https://evil.example/recharge?session={CHECKOUT_SESSION_ID}","https://cheerfulmusic.com.evil.example/?session={CHECKOUT_SESSION_ID}","https://evil@cheerfulmusic.com/?session={CHECKOUT_SESSION_ID}","http://cheerfulmusic.com/?session={CHECKOUT_SESSION_ID}","https://cheerfulmusic.com/recharge","https://cheerfulmusic.com/?session={CHECKOUT_SESSION_ID}#fragment","http://localhost:5173/?session={CHECKOUT_SESSION_ID}"}) {
            ReflectionTestUtils.setField(gateway,"successUrl",url);assertFalse(gateway.configured(),url);
        }
        ReflectionTestUtils.setField(gateway,"successUrl","");assertTrue(gateway.configured());
        ReflectionTestUtils.setField(gateway,"cancelUrl","https://cheerfulmusic.com/?session={CHECKOUT_SESSION_ID}");assertFalse(gateway.configured());
    }
    @Test void isolatedSandboxMayReturnToLocalhostButLiveMayNot() {
        var gateway=gateway("sandbox","sk_test_offline_fixture");
        ReflectionTestUtils.setField(gateway,"successUrl","http://127.0.0.1:18080/recharge?session={CHECKOUT_SESSION_ID}");
        ReflectionTestUtils.setField(gateway,"cancelUrl","http://localhost:5173/recharge?cancelled=1");assertTrue(gateway.configured());
        ReflectionTestUtils.setField(gateway,"environment","live");ReflectionTestUtils.setField(gateway,"apiKey","sk_live_offline_fixture");assertFalse(gateway.configured());
    }
    @Test void resourcePathInjectionAndFractionalCentAmountsFailWithoutNetwork() {
        var gateway=gateway("sandbox","sk_test_offline_fixture");ReflectionTestUtils.setField(gateway,"accountVerified",true);
        assertEquals(400,assertThrows(ResponseStatusException.class,()->gateway.retrieveRefund("../charges/ch_other")).getStatusCode().value());
        assertThrows(ArithmeticException.class,()->gateway.createSession("fixture","Credits",new java.math.BigDecimal("9.901"),"USD"));
        assertThrows(IllegalArgumentException.class,()->gateway.createSession("fixture","Credits",new java.math.BigDecimal("9.90"),"GBP"));
    }
}
