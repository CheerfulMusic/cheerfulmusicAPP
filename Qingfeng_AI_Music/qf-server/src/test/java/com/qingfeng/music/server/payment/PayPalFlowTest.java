package com.qingfeng.music.server.payment;

import com.qingfeng.music.credits.CreditsLedger;
import com.qingfeng.music.identity.api.IdentityApi;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:paypal_flow;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","qingfeng.paypal.enabled=true","qingfeng.paypal.client-id=fake","qingfeng.paypal.client-secret=fake","qingfeng.paypal.merchant-id=TESTMERCHANT","qingfeng.paypal.webhook-id=fake"})
@ActiveProfiles("test")
class PayPalFlowTest {
 @Autowired PayPalPaymentService actual; @Autowired IdentityApi identity; @Autowired JdbcTemplate db; @Autowired CreditsLedger ledger;
 @Test void sdkConfigurationExposesOnlyPublicInformation(){
  var config=actual.sdkConfig();
  assertEquals(java.util.Set.of("enabled","clientId","environment"),config.keySet());
  assertEquals(true,config.get("enabled"));assertEquals("fake",config.get("clientId"));assertEquals("sandbox",config.get("environment"));
 }
 @Test void capturesOnlyMatchingApprovedOrderAndDoesNotDoubleCredit(){
  String owner=identity.create().creatorId(), id=UUID.randomUUID().toString();
  db.update("INSERT INTO payment_order(id,creator_id,package_id,amount,currency,credits,provider_link_id,status,created_at,provider) VALUES(?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,?)",id,owner,"starter-GBP",new BigDecimal("4.99"),"GBP",100,"REMOTE123","PENDING","PAYPAL_SANDBOX");
  var gateway=mock(PayPalCheckoutGateway.class);var service=spy(actual);doReturn(gateway).when(service).gateway();
  var wrong=new PayPalCheckoutGateway.Snapshot("REMOTE123",id,"APPROVED","TESTMERCHANT","","",new BigDecimal("4.99"),"USD");
  when(gateway.retrieve("REMOTE123")).thenReturn(wrong);
  assertThrows(ResponseStatusException.class,()->service.capture(owner,id));verify(gateway,never()).capture(anyString(),anyString());
  when(gateway.retrieve("REMOTE123")).thenReturn(new PayPalCheckoutGateway.Snapshot("REMOTE123",id,"APPROVED","OTHER","","",new BigDecimal("4.99"),"GBP"));
  assertThrows(ResponseStatusException.class,()->service.capture(owner,id));
  when(gateway.retrieve("REMOTE123")).thenReturn(new PayPalCheckoutGateway.Snapshot("REMOTE123",id,"APPROVED","TESTMERCHANT","","",new BigDecimal("0.01"),"GBP"));
  assertThrows(ResponseStatusException.class,()->service.capture(owner,id));verify(gateway,never()).capture(anyString(),anyString());
  var approved=new PayPalCheckoutGateway.Snapshot("REMOTE123",id,"APPROVED","TESTMERCHANT","","",new BigDecimal("4.99"),"GBP");
  var completed=new PayPalCheckoutGateway.Snapshot("REMOTE123",id,"COMPLETED","TESTMERCHANT","CAP1","COMPLETED",new BigDecimal("4.99"),"GBP");
  when(gateway.retrieve("REMOTE123")).thenReturn(approved);when(gateway.capture("REMOTE123","c"+id)).thenReturn(completed);
  assertEquals("PAID",service.capture(owner,id).get("status"));service.capture(owner,id);
  assertEquals(100,ledger.balance(owner));verify(gateway,times(1)).capture("REMOTE123","c"+id);
 }
 @Test void unverifiedWebhookIsRejected(){
  var gateway=mock(PayPalCheckoutGateway.class);var service=spy(actual);doReturn(gateway).when(service).gateway();
  var headers=new PayPalCheckoutGateway.WebhookHeaders("a","b","c","d","e");
  when(gateway.verifyWebhook(any(),any())).thenReturn(false);
  assertEquals(401,assertThrows(ResponseStatusException.class,()->service.webhook(headers,"{}".getBytes())).getStatusCode().value());
 }
}
