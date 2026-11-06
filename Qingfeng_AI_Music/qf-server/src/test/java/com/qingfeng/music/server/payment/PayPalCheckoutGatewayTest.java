package com.qingfeng.music.server.payment;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class PayPalCheckoutGatewayTest {
    private final ObjectMapper json=new ObjectMapper();
    private final ArrayList<Map<String,String>> headers=new ArrayList<>();
    private String response;
    private final PayPalCheckoutGateway gateway=new PayPalCheckoutGateway(PayPalCheckoutGateway.Environment.SANDBOX,"MERCHANT1","WEBHOOK1",(method,path,h,body)->{headers.add(h);return json.readTree(response);});
    @Test void createUsesStableRequestIdAndRejectsOtherHosts() {
        response="{\"id\":\"ORDER123\",\"links\":[{\"rel\":\"payer-action\",\"href\":\"https://www.sandbox.paypal.com/checkoutnow?token=ORDER123\"}]}";
        var created=gateway.create("reference-123","Credits",new BigDecimal("5.00"),"GBP","https://cheerfulmusic.com/account","https://cheerfulmusic.com/account");
        assertEquals("ORDER123",created.orderId());assertEquals("reference-123",headers.get(0).get("PayPal-Request-Id"));
        response=response.replace("www.sandbox.paypal.com","evil.example");
        assertThrows(IllegalStateException.class,()->gateway.create("reference-123","Credits",new BigDecimal("5.00"),"GBP","https://cheerfulmusic.com/account","https://cheerfulmusic.com/account"));
    }
    @Test void captureSnapshotUsesCaptureAmountAndChecksMerchantAndIdentity() {
        response="{\"id\":\"ORDER123\",\"status\":\"COMPLETED\",\"purchase_units\":[{\"custom_id\":\"our-order\",\"payee\":{\"merchant_id\":\"MERCHANT1\"},\"amount\":{\"value\":\"10.00\",\"currency_code\":\"GBP\"},\"payments\":{\"captures\":[{\"id\":\"CAP123\",\"status\":\"COMPLETED\",\"amount\":{\"value\":\"9.00\",\"currency_code\":\"USD\"}}]}}]}";
        var snapshot=gateway.capture("ORDER123","capture-123");
        assertEquals("our-order",snapshot.reference());assertEquals("MERCHANT1",snapshot.payeeId());assertEquals("COMPLETED",snapshot.captureStatus());assertEquals(new BigDecimal("9.00"),snapshot.amount());assertEquals("USD",snapshot.currency());assertEquals("capture-123",headers.get(0).get("PayPal-Request-Id"));
        response=response.replace("MERCHANT1","OTHER");assertThrows(IllegalStateException.class,()->gateway.retrieve("ORDER123"));
        response=response.replace("OTHER","MERCHANT1");assertThrows(IllegalStateException.class,()->gateway.retrieve("ORDER999"));
    }
    @Test void webhookMustBeVerifiedByPaypal() {
        var h=new PayPalCheckoutGateway.WebhookHeaders("SHA256withRSA","https://example.test/cert","id","signature","2026-11-05T00:00:00Z");
        response="{\"verification_status\":\"SUCCESS\"}";assertTrue(gateway.verifyWebhook(h,"{\"id\":\"WH-1\"}".getBytes(StandardCharsets.UTF_8)));
        response="{\"verification_status\":\"FAILURE\"}";assertFalse(gateway.verifyWebhook(h,"{}".getBytes(StandardCharsets.UTF_8)));
        assertFalse(gateway.verifyWebhook(null,"{}".getBytes(StandardCharsets.UTF_8)));
    }
    @Test void resumeAcceptsOnlyPaypalSandboxApprovalUrl() {
        response="{\"id\":\"ORDER123\",\"status\":\"CREATED\",\"purchase_units\":[{\"custom_id\":\"our-order\",\"payee\":{\"merchant_id\":\"MERCHANT1\"},\"amount\":{\"value\":\"4.99\",\"currency_code\":\"GBP\"}}],\"links\":[{\"rel\":\"payer-action\",\"href\":\"https://www.sandbox.paypal.com/checkoutnow?token=ORDER123\"}]}";
        assertTrue(gateway.retrieveCheckout("ORDER123").approvalUrl().startsWith("https://www.sandbox.paypal.com/"));
        response=response.replace("www.sandbox.paypal.com","evil.example");
        assertThrows(IllegalStateException.class,()->gateway.retrieveCheckout("ORDER123"));
    }
}
