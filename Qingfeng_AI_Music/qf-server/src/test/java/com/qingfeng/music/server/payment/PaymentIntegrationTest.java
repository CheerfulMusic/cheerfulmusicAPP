package com.qingfeng.music.server.payment;

import com.qingfeng.music.credits.CreditsLedger;
import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.server.security.CreatorPrincipal;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
 "spring.datasource.url=jdbc:h2:mem:payments;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
 "qingfeng.payments.client-id=test-client", "qingfeng.payments.api-key=test-key",
 "qingfeng.payments.account-id=acct_test", "qingfeng.payments.webhook-secret=test-secret"})
@ActiveProfiles("test")
class PaymentIntegrationTest {
 @Autowired PaymentController controller; @Autowired PaymentService payments; @Autowired IdentityApi identity; @Autowired CreditsLedger ledger; @Autowired JdbcTemplate db;
 @Value("${local.server.port}") int port;
 private String sign(String timestamp,String body)throws Exception {
   Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec("test-secret".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
   return HexFormat.of().formatHex(mac.doFinal((timestamp+body).getBytes(StandardCharsets.UTF_8)));
 }
 private int post(String timestamp,String signature,String body)throws Exception {
   var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/public/payments/airwallex/webhook"))
    .header("Content-Type","application/json").header("x-timestamp",timestamp).header("x-signature",signature)
    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
   return HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString()).statusCode();
 }
 @Test void publicWebhookUsesRawBodySignatureAndDoesNotRequireSessionOrCsrf()throws Exception {
   String time=Long.toString(System.currentTimeMillis());String body="{\"account_id\":\"acct_test\",\"name\":\"payment_link.created\",\"data\":{}}";
   assertEquals(200,post(time,sign(time,body),body));
   assertEquals(401,post(time,sign(time,body),body+" "));
   String old=Long.toString(System.currentTimeMillis()-600000);assertEquals(401,post(old,sign(old,body),body));
   String wrong="{\"account_id\":\"other\",\"name\":\"payment_link.created\"}";assertEquals(403,post(time,sign(time,wrong),wrong));
 }
 @Test void catalogIsClosedByDefault() {assertEquals(false,controller.catalog().get("enabled"));assertEquals(0,((java.util.List<?>)controller.catalog().get("packages")).size());}
 @Test void airwallexEndpointsDoNotExposePaypalOrders() {
   String owner=identity.create().creatorId(), id=java.util.UUID.randomUUID().toString();
   db.update("INSERT INTO payment_order(id,creator_id,package_id,amount,currency,credits,status,created_at,provider) VALUES(?,?,?,?,?,?,?,CURRENT_TIMESTAMP,?)",id,owner,"starter-GBP",new java.math.BigDecimal("4.99"),"GBP",100,"PENDING","PAYPAL_SANDBOX");
   var orders=(java.util.List<?>)controller.orders(new CreatorPrincipal(owner)).get("orders");
   assertTrue(orders.isEmpty());
   assertThrows(org.springframework.web.server.ResponseStatusException.class,()->controller.order(new CreatorPrincipal(owner),id));
 }
 @Test void orderHistoryIsBoundedAndOnlyVisibleToOwner() {
   String owner=identity.create().creatorId(), stranger=identity.create().creatorId();
   String otherId=java.util.UUID.randomUUID().toString();
   for(int i=0;i<51;i++) db.update("INSERT INTO payment_order(id,creator_id,package_id,amount,currency,credits,status,created_at) VALUES(?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",java.util.UUID.randomUUID().toString(),owner,"p",1,"USD",10,"CREATING");
   db.update("INSERT INTO payment_order(id,creator_id,package_id,amount,currency,credits,status,created_at) VALUES(?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",otherId,stranger,"p",1,"USD",10,"PENDING");
   var rows=(java.util.List<?>)controller.orders(new CreatorPrincipal(owner)).get("orders");
   assertEquals(50,rows.size());
   assertTrue(rows.stream().noneMatch(row->((java.util.Map<?,?>)row).containsValue(otherId)));
   assertEquals(java.util.Set.of("orderId","status","credits","createdAt"),((java.util.Map<?,?>)rows.get(0)).keySet());
   assertThrows(org.springframework.web.server.ResponseStatusException.class,()->controller.order(new CreatorPrincipal(owner),otherId));
 }
 @Test void concurrentReservationsAllowOnlyOneUnresolvedOrder() throws Exception {
   var creator=identity.create(); String owner=creator.creatorId();
   identity.signInWithGoogle(creator.token(),owner,java.util.UUID.randomUUID().toString(),null,null);
   var start=new java.util.concurrent.CountDownLatch(1);
   var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
   java.util.concurrent.Callable<Boolean> create=()->{start.await();try {payments.reserve(owner,java.util.UUID.randomUUID().toString(),"p",new java.math.BigDecimal("1.00"),"USD",10);return true;} catch(org.springframework.web.server.ResponseStatusException e) {assertEquals(409,e.getStatusCode().value());return false;}};
   try {
     var first=executor.submit(create);var second=executor.submit(create);start.countDown();
     assertNotEquals(first.get(10,java.util.concurrent.TimeUnit.SECONDS),second.get(10,java.util.concurrent.TimeUnit.SECONDS));
     assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM payment_order WHERE creator_id=? AND status='CREATING'",Integer.class,owner));
     assertThrows(org.springframework.web.server.ResponseStatusException.class,()->payments.reserve(owner,java.util.UUID.randomUUID().toString(),"p",new java.math.BigDecimal("1.00"),"USD",10));
   } finally {executor.shutdownNow();}
 }
 @Test void validatesAmountAndCreditsOnlyOnce() {
   String owner=identity.create().creatorId(), id=java.util.UUID.randomUUID().toString();
   db.update("INSERT INTO payment_order(id,creator_id,package_id,amount,currency,credits,provider_link_id,status,created_at) VALUES(?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",id,owner,"p",new java.math.BigDecimal("5.00"),"USD",10,"link1","PENDING");
   var mapper=new ObjectMapper();
   String json="{\"id\":\"link1\",\"reference\":\""+id+"\",\"status\":\"PAID\",\"reusable\":false,\"currency\":\"USD\",\"amount\":5.00,\"latest_successful_payment_intent_id\":\"int_1\"}";
   assertThrows(Exception.class,()->payments.settle(id,"link1",AirwallexSandboxGateway.snapshot(mapper.readTree(json.replace("5.00","6.00")))));
   assertEquals(0,ledger.balance(owner));
   payments.settle(id,"link1",AirwallexSandboxGateway.snapshot(mapper.readTree(json)));payments.settle(id,"link1",AirwallexSandboxGateway.snapshot(mapper.readTree(json)));
   assertEquals(10,ledger.balance(owner));
 }

 @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
 @Autowired PaymentCatalog catalog;
 private PaymentService service(PaymentGateway gateway) {
   var result=new PaymentService(db,new org.springframework.transaction.support.TransactionTemplate(transactionManager),ledger,gateway,catalog);
   org.springframework.test.util.ReflectionTestUtils.setField(result,"enabled",true);
   return result;
 }
 private CreatorPrincipal signedInOwner() {
   var session=identity.create();
   identity.signInWithGoogle(session.token(),session.creatorId(),java.util.UUID.randomUUID().toString(),null,null);
   return new CreatorPrincipal(session.creatorId());
 }
 @Test void definiteRejectionReleasesOrderButUnknownCreateMustBeRecoveredWithoutRecreating() {
   var gateway=org.mockito.Mockito.mock(PaymentGateway.class);
   org.mockito.Mockito.when(gateway.configured()).thenReturn(true);
   var local=service(gateway);var owner=signedInOwner();
   org.mockito.Mockito.when(gateway.create(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString()))
     .thenThrow(new PaymentGateway.CreationRejected()).thenThrow(new IllegalStateException("timeout"));
   assertThrows(org.springframework.web.server.ResponseStatusException.class,()->local.create(owner,new PaymentService.OrderRequest("starter-USD")));
   assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM payment_order WHERE creator_id=? AND status='FAILED'",Integer.class,owner.id()));
   assertThrows(IllegalStateException.class,()->local.create(owner,new PaymentService.OrderRequest("starter-USD")));
   String id=db.queryForObject("SELECT id FROM payment_order WHERE creator_id=? AND status='CREATING'",String.class,owner.id());
   assertThrows(org.springframework.web.server.ResponseStatusException.class,()->local.create(owner,new PaymentService.OrderRequest("starter-USD")));
   var remote=new PaymentGateway.Snapshot("original",id,"PAID",false,"USD",new java.math.BigDecimal("4.99"),"intent_original");
   org.mockito.Mockito.when(gateway.retrieve("original")).thenReturn(remote);
   assertThrows(org.springframework.web.server.ResponseStatusException.class,()->local.recover(signedInOwner(),id,"original"));
   org.mockito.Mockito.when(gateway.retrieve("wrong")).thenReturn(new PaymentGateway.Snapshot("wrong",id,"PAID",false,"USD",new java.math.BigDecimal("0.01"),"intent_wrong"));
   assertThrows(org.springframework.web.server.ResponseStatusException.class,()->local.recover(owner,id,"wrong"));
   assertEquals("CREATING",db.queryForObject("SELECT status FROM payment_order WHERE id=?",String.class,id));
   long before=ledger.balance(owner.id());
   assertEquals("PAID",local.recover(owner,id,"original").get("status"));
   local.recover(owner,id,"original");
   assertEquals(before+100,ledger.balance(owner.id()));
   org.mockito.Mockito.verify(gateway,org.mockito.Mockito.times(2)).create(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString());
 }
}
