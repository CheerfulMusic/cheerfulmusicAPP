package com.qingfeng.music.server.payment;

import com.qingfeng.music.infrastructure.persistence.credits.JdbcFencedCreditsLedger;
import com.qingfeng.music.server.config.CreditSchemaReadiness;
import com.qingfeng.music.server.security.CreatorPrincipal;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Actual service/controller binding, H2 order SQL and transaction tests with synthetic
 * supplier facts. Financial ledger mutations, schema metadata and Inbox leases are
 * mocked. This is not MySQL, HTTP security, Apple device or Sandbox acceptance.
 */
class StripeWalletPaymentServiceTest {
    private static final String PROVIDER="STRIPE_SANDBOX",MERCHANT="acct_walletfixture12345678";
    private final JsonMapper json=JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private final JdbcFencedCreditsLedger ledger=mock(JdbcFencedCreditsLedger.class);
    private final StripeCheckoutGateway gateway=mock(StripeCheckoutGateway.class);
    private final PaymentInbox inbox=mock(PaymentInbox.class);
    private final Map<String,JsonNode> sessions=new HashMap<>();
    private final List<CheckoutCall> creates=new ArrayList<>();
    private JdbcTemplate db;
    private TransactionTemplate tx;
    private StripePaymentService service;
    private MockEnvironment environment;
    private record CheckoutCall(String order,BigDecimal amount,String currency,String method){}

    @BeforeEach void startAnOwnedMemoryDatabaseOnly(){
        var source=new DriverManagerDataSource("jdbc:h2:mem:stripe_wallet_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        source.setDriverClassName("org.h2.Driver");
        var actual=new JdbcTemplate(source);
        actual.execute("CREATE TABLE creator(id VARCHAR(36) PRIMARY KEY)");
        actual.execute("CREATE TABLE creator_google_identity(creator_id VARCHAR(36) NOT NULL)");
        actual.execute("CREATE TABLE creator_oidc_identity(creator_id VARCHAR(36) NOT NULL)");
        actual.execute("CREATE TABLE payment_order(id VARCHAR(36) PRIMARY KEY,creator_id VARCHAR(36) NOT NULL,package_id VARCHAR(64) NOT NULL,amount DECIMAL(12,2) NOT NULL,currency VARCHAR(3) NOT NULL,credits BIGINT NOT NULL,status VARCHAR(32) NOT NULL,created_at TIMESTAMP NOT NULL,provider VARCHAR(32) NOT NULL,provider_link_id VARCHAR(128),provider_capture_id VARCHAR(128))");
        db=spy(actual);tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        // H2 cannot prove production MySQL structure/readiness. Keep that scope explicit.
        for(var check:CreditSchemaReadiness.CHECKS.values())
            doReturn(check.expected()).when(db).queryForObject(check.sql(),Integer.class);
        when(gateway.configured()).thenReturn(true);when(gateway.provider()).thenReturn(PROVIDER);
        when(gateway.merchantId()).thenReturn(MERCHANT);when(gateway.environment()).thenReturn("sandbox");when(gateway.isLive()).thenReturn(false);
        when(gateway.createSession(anyString(),anyString(),any(BigDecimal.class),anyString())).thenAnswer(call->created(call.getArgument(0),call.getArgument(2),call.getArgument(3),"card"));
        when(gateway.createSession(anyString(),anyString(),any(BigDecimal.class),anyString(),anyString())).thenAnswer(call->created(call.getArgument(0),call.getArgument(2),call.getArgument(3),call.getArgument(4)));
        when(gateway.retrieveSession(anyString())).thenAnswer(call->{var result=sessions.get(call.getArgument(0));assertNotNull(result,"Only registered synthetic supplier resources may be read");return result;});
        doAnswer(call->{((Runnable)call.getArgument(2)).run();return null;}).when(inbox).completeOwned(any(),anyString(),any(),anyString());
        doAnswer(call->{((Runnable)call.getArgument(2)).run();return null;}).when(inbox).waitForProvider(any(),anyString(),any(),anyString());
        environment=walletEnvironment("GB",true);service=service(environment);
    }

    @Test void schemaReuseRequiresAnActiveWriterAndRevalidatesEachNewEpoch() {
        ReflectionTestUtils.setField(service,"reuseSchemaCheck",true);
        String identitySql = "SELECT CONCAT(@@server_uuid";
        doReturn("server/db/epoch1").when(db).queryForObject(startsWith(identitySql),eq(String.class));
        assertEquals(true,service.catalog().get("enabled"));

        doThrow(new org.springframework.dao.EmptyResultDataAccessException(1))
                .when(db).queryForObject(startsWith(identitySql),eq(String.class));
        assertEquals(false,service.catalog().get("enabled"));

        doReturn("server/db/epoch2").when(db).queryForObject(startsWith(identitySql),eq(String.class));
        var history = CreditSchemaReadiness.CHECKS.get("v45_exact_history");
        doReturn(0).when(db).queryForObject(history.sql(),Integer.class);
        assertEquals(false,service.catalog().get("enabled"));
        doReturn(history.expected()).when(db).queryForObject(history.sql(),Integer.class);
        assertEquals(true,service.catalog().get("enabled"));
    }

    @AfterEach void stopOnlyTheOwnedMemoryDatabase(){if(db!=null)db.execute("SHUTDOWN");}

    private MockEnvironment walletEnvironment(String country,boolean wechat){return new MockEnvironment()
        .withProperty("qingfeng.stripe.business-country",country)
        .withProperty("qingfeng.stripe.payment-methods.wechat-pay.enabled",Boolean.toString(wechat))
        .withProperty("qingfeng.stripe.prices.cny.starter","68.80")
        .withProperty("qingfeng.stripe.prices.cny.standard","178.80")
        .withProperty("qingfeng.stripe.prices.cny.plus","338.80");}

    private StripePaymentService service(MockEnvironment settings){
        var result=new StripePaymentService(db,tx,ledger,new PaymentCatalog(settings),gateway,json,inbox,settings);
        ReflectionTestUtils.setField(result,"enabled",true);ReflectionTestUtils.setField(result,"acceptingNewOrders",true);return result;
    }
    private CreatorPrincipal owner(){String owner=UUID.randomUUID().toString();db.update("INSERT INTO creator VALUES(?)",owner);db.update("INSERT INTO creator_google_identity VALUES(?)",owner);return new CreatorPrincipal(owner);}
    private String order(Map<String,Object> response){return (String)response.get("orderId");}
    private Map<String,Object> stored(String order){return db.queryForMap("SELECT * FROM payment_order WHERE id=?",order);}
    private long number(String sql,Object...args){return db.queryForObject(sql,Long.class,args);}
    private JsonNode created(String order,BigDecimal amount,String currency,String method){
        creates.add(new CheckoutCall(order,amount,currency,method));String id="cs_test_"+UUID.randomUUID().toString().replace("-","");
        JsonNode result=session(id,order,amount,currency,method,"open","unpaid");sessions.put(id,result);return result;
    }
    private JsonNode session(String id,String order,BigDecimal amount,String currency,String method,String status,String paid){
        return json.valueToTree(Map.ofEntries(Map.entry("id",id),Map.entry("url","https://checkout.stripe.com/c/pay/"+id+"#synthetic"),
            Map.entry("status",status),Map.entry("payment_status",paid),Map.entry("payment_intent","pi_"+id.substring(8)),Map.entry("metadata",Map.of("order_id",order)),
            Map.entry("client_reference_id",order),Map.entry("mode","payment"),Map.entry("amount_total",amount.movePointRight(2).longValueExact()),
            Map.entry("currency",currency.toLowerCase(java.util.Locale.ROOT)),Map.entry("livemode",false),Map.entry("payment_method_types",List.of(method))));
    }
    private String sessionId(String order){return (String)stored(order).get("provider_link_id");}
    private void fact(String order,String status,String paymentStatus){var row=stored(order);String id=(String)row.get("provider_link_id");sessions.put(id,session(id,order,(BigDecimal)row.get("amount"),(String)row.get("currency"),StripePaymentMethods.checkoutMethodForStoredOffer((String)row.get("package_id")),status,paymentStatus));}
    private PaymentInbox.Delivery due(String order,String kind){
        String event="evt_"+UUID.randomUUID().toString().replace("-","");String pointer=json.writeValueAsString(Map.of("provider","STRIPE","merchant",MERCHANT,"environment","sandbox","event",event,"resource",sessionId(order)));
        var delivery=new PaymentInbox.Delivery(PROVIDER,event,kind,pointer,"synthetic-wallet-lease",1,MERCHANT,"sandbox");
        when(inbox.claim(PROVIDER,MERCHANT,"sandbox")).thenReturn(Optional.of(delivery));return delivery;
    }

    @Test void oldApiAndExplicitApplePayUseTheExistingHostedCardFamily(){
        var legacyOwner=owner();var legacy=service.create(legacyOwner,"starter-USD");
        var walletOwner=owner();var wallet=service.create(walletOwner,"starter-USD","apple_pay");
        assertEquals("starter-USD",stored(order(legacy)).get("package_id"));assertEquals("starter-USD",stored(order(wallet)).get("package_id"));
        assertEquals(2,creates.size());for(var call:creates){assertEquals("card",call.method());assertEquals("USD",call.currency());assertEquals(new BigDecimal("9.90"),call.amount());}
        verify(gateway,times(2)).createSession(anyString(),anyString(),eq(new BigDecimal("9.90")),eq("USD"));
        verify(gateway,never()).createSession(anyString(),anyString(),any(),anyString(),anyString());verifyNoInteractions(ledger);
    }

    @Test void controllerAcceptsWalletSelectionButIgnoresClientSuppliedPriceCurrencyAndCredits()throws Exception{
        var owner=owner();var request=json.readValue("{\"packageId\":\"starter-CNY-wechat_pay\",\"paymentMethod\":\"wechat_pay\",\"amount\":0.01,\"currency\":\"USD\",\"credits\":999999}",StripePaymentController.OrderRequest.class);
        var response=new StripePaymentController(service).create(owner,request);var saved=stored(order(response));
        assertEquals("starter-CNY-wechat_pay",saved.get("package_id"));assertEquals(new BigDecimal("68.80"),saved.get("amount"));assertEquals("CNY",saved.get("currency"));assertEquals(100,((Number)saved.get("credits")).longValue());
        assertEquals(List.of(new CheckoutCall(order(response),new BigDecimal("68.80"),"CNY","wechat_pay")),creates);verifyNoInteractions(ledger);
    }

    @Test void allCnyOffersPersistApprovedFixedSnapshotsAndNeverConvertDollarPrices(){
        String[] names={"mini","starter","standard","plus"},amounts={"29.90","68.80","178.80","338.80"};long[] credits={35,100,300,700};
        for(int i=0;i<names.length;i++){
            var result=service.create(owner(),names[i]+"-CNY-wechat_pay","wechat_pay");var row=stored(order(result));
            assertEquals(new BigDecimal(amounts[i]),row.get("amount"));assertEquals("CNY",row.get("currency"));assertEquals(credits[i],((Number)row.get("credits")).longValue());
            assertEquals("wechat_pay",creates.get(i).method());assertEquals(new BigDecimal(amounts[i]),creates.get(i).amount());
        }
        assertEquals(4,number("SELECT COUNT(*) FROM payment_order"));verify(gateway,never()).createSession(anyString(),anyString(),any(),anyString());verifyNoInteractions(ledger);
    }

    @Test void gbWechatUsdIsRejectedBeforeCreatingAnyLocalOrRemoteOrder(){
        var failure=assertThrows(ResponseStatusException.class,()->service.create(owner(),"starter-USD-wechat_pay","wechat_pay"));
        assertTrue(List.of(400,503).contains(failure.getStatusCode().value()));assertEquals(0,number("SELECT COUNT(*) FROM payment_order"));assertTrue(creates.isEmpty());verifyNoInteractions(ledger);
    }

    @Test void usCountryCanSelectSeparateUsdWechatOfferWithoutChangingExistingCardOffer(){
        var us=service(walletEnvironment("US",true));var result=us.create(owner(),"starter-USD-wechat_pay","wechat_pay");
        assertEquals("starter-USD-wechat_pay",stored(order(result)).get("package_id"));assertEquals(new BigDecimal("9.90"),stored(order(result)).get("amount"));assertEquals("USD",stored(order(result)).get("currency"));assertEquals("wechat_pay",creates.get(0).method());
        var card=us.create(owner(),"starter-USD","card");assertEquals(new BigDecimal("9.90"),stored(order(card)).get("amount"));assertEquals("card",creates.get(1).method());
    }

    @Test void disabledWechatAndUnsupportedOrCrossMethodOffersCannotFallBackToCard(){
        var closed=service(walletEnvironment("GB",false));assertThrows(ResponseStatusException.class,()->closed.create(owner(),"starter-CNY-wechat_pay","wechat_pay"));
        assertThrows(ResponseStatusException.class,()->service.create(owner(),"starter-CNY-wechat_pay","card"));
        assertThrows(ResponseStatusException.class,()->service.create(owner(),"starter-CNY-wechat_pay","apple_pay"));
        assertThrows(ResponseStatusException.class,()->service.create(owner(),"starter-USD","invented_wallet"));
        assertThrows(ResponseStatusException.class,()->service.create(owner(),"attacker-CNY-wechat_pay","wechat_pay"));
        assertEquals(0,number("SELECT COUNT(*) FROM payment_order"));assertTrue(creates.isEmpty());verifyNoInteractions(ledger);
    }

    @Test void newPurchasePreflightRejectionCreatesNoOrderAndDoesNotBlockAnAuthorizedRetry(){
        var cardOwner=owner();var wechatOwner=owner();
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"Synthetic merchant cannot accept this purchase")).when(gateway).verifyNewPurchase("card","USD");
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"Synthetic wallet country is not eligible")).when(gateway).verifyNewPurchase("wechat_pay","CNY");
        assertEquals(503,assertThrows(ResponseStatusException.class,()->service.create(cardOwner,"starter-USD","card")).getStatusCode().value());
        assertEquals(503,assertThrows(ResponseStatusException.class,()->service.create(wechatOwner,"starter-CNY-wechat_pay","wechat_pay")).getStatusCode().value());
        assertEquals(0,number("SELECT COUNT(*) FROM payment_order"));assertTrue(creates.isEmpty());verify(gateway,never()).createSession(anyString(),anyString(),any(),anyString());verify(gateway,never()).createSession(anyString(),anyString(),any(),anyString(),anyString());verifyNoInteractions(ledger);
        doNothing().when(gateway).verifyNewPurchase("card","USD");var accepted=service.create(cardOwner,"starter-USD","card");
        assertEquals("PENDING",stored(order(accepted)).get("status"));assertEquals(1,number("SELECT COUNT(*) FROM payment_order"));assertEquals(1,creates.size());
    }

    @Test void unknownWechatCreationResumesTheOriginalSnapshotAfterPricesAndNewPurchaseFlagsChange(){
        var owner=owner();doThrow(new StripeCheckoutGateway.StripeRemoteException(504)).when(gateway).createSession(anyString(),anyString(),any(),eq("CNY"),eq("wechat_pay"));
        assertEquals(502,assertThrows(ResponseStatusException.class,()->service.create(owner,"starter-CNY-wechat_pay","wechat_pay")).getStatusCode().value());
        assertEquals(1,number("SELECT COUNT(*) FROM payment_order"));String order=db.queryForObject("SELECT id FROM payment_order",String.class);
        assertNotEquals("PAID",stored(order).get("status"));assertNull(stored(order).get("provider_link_id"));
        var changed=walletEnvironment("GB",false).withProperty("qingfeng.stripe.prices.cny.starter","199.00");var recovering=service(changed);ReflectionTestUtils.setField(recovering,"acceptingNewOrders",false);
        doAnswer(call->created(call.getArgument(0),call.getArgument(2),call.getArgument(3),call.getArgument(4))).when(gateway).createSession(anyString(),anyString(),any(),eq("CNY"),eq("wechat_pay"));
        var result=recovering.resume(owner,order);
        assertEquals(order,result.get("orderId"));assertEquals("PENDING",result.get("status"));assertEquals(1,number("SELECT COUNT(*) FROM payment_order"));
        assertEquals(new BigDecimal("68.80"),stored(order).get("amount"));assertEquals("CNY",stored(order).get("currency"));assertEquals("starter-CNY-wechat_pay",stored(order).get("package_id"));
        assertEquals(List.of(new CheckoutCall(order,new BigDecimal("68.80"),"CNY","wechat_pay")),creates);verify(gateway,never()).createSession(anyString(),anyString(),any(),anyString());verifyNoInteractions(ledger);
    }

    @Test void verifiedMiniCnyCompletionAndDifferentAsyncEventsGrantOnlyTheStoredCreditsOnce(){
        var owner=owner();String order=order(service.create(owner,"mini-CNY-wechat_pay","wechat_pay"));fact(order,"complete","paid");
        var one=due(order,"checkout.session.completed");assertTrue(service.processNextEvent());
        var duplicate=due(order,"checkout.session.async_payment_succeeded");assertTrue(service.processNextEvent());
        assertEquals("PAID",stored(order).get("status"));verify(ledger,times(1)).credit(owner.id(),35L,"payment:"+order);
        verify(ledger,times(2)).bindPayment("STRIPE",MERCHANT,"sandbox",owner.id(),order,sessions.get(sessionId(order)).path("payment_intent").asString(),"CNY");
        verify(inbox).completeOwned(eq(one),eq(owner.id()),any(),anyString());verify(inbox).completeOwned(eq(duplicate),eq(owner.id()),any(),anyString());verify(inbox,never()).retry(any());
        var view=service.order(owner,order);assertEquals("CNY",view.get("currency"));assertEquals(new BigDecimal("29.90"),view.get("amount"));assertEquals("wechat_pay",view.get("paymentMethod"));
    }

    @Test void completedButUnpaidWechatWaitsForAuthoritativeSuccessWithoutIssuingCredits(){
        var owner=owner();String order=order(service.create(owner,"starter-CNY-wechat_pay","wechat_pay"));fact(order,"complete","unpaid");
        var pending=due(order,"checkout.session.completed");assertTrue(service.processNextEvent());
        assertEquals("PENDING",stored(order).get("status"));verify(inbox).waitForProvider(eq(pending),eq(owner.id()),any(),anyString());verify(inbox,never()).completeOwned(any(),anyString(),any(),anyString());verifyNoInteractions(ledger);
        fact(order,"complete","paid");due(order,"checkout.session.async_payment_succeeded");assertTrue(service.processNextEvent());
        assertEquals("PAID",stored(order).get("status"));verify(ledger,times(1)).credit(owner.id(),100L,"payment:"+order);
    }

    @Test void asynchronousFailureOrExpiryEndsOnlyItsOriginalUnpaidOrder(){
        var firstOwner=owner();String first=order(service.create(firstOwner,"starter-CNY-wechat_pay","wechat_pay"));fact(first,"complete","unpaid");var failed=due(first,"checkout.session.async_payment_failed");assertTrue(service.processNextEvent());assertEquals("FAILED",stored(first).get("status"));
        var secondOwner=owner();String second=order(service.create(secondOwner,"starter-CNY-wechat_pay","wechat_pay"));fact(second,"expired","unpaid");var expired=due(second,"checkout.session.expired");assertTrue(service.processNextEvent());assertEquals("CANCELLED",stored(second).get("status"));
        assertEquals("FAILED",stored(first).get("status"));verify(inbox).completeOwned(eq(failed),eq(firstOwner.id()),any(),anyString());verify(inbox).completeOwned(eq(expired),eq(secondOwner.id()),any(),anyString());verifyNoInteractions(ledger);
    }

    @Test void cnyCompletionWithWrongCurrencyAmountOrOrderCannotIssueCredits(){
        for(String mismatch:List.of("currency","amount","order")){
            var owner=owner();String order=order(service.create(owner,"starter-CNY-wechat_pay","wechat_pay"));String id=sessionId(order);
            String currency=mismatch.equals("currency")?"USD":"CNY",remoteOrder=mismatch.equals("order")?UUID.randomUUID().toString():order;BigDecimal amount=mismatch.equals("amount")?new BigDecimal("0.01"):new BigDecimal("68.80");
            sessions.put(id,session(id,remoteOrder,amount,currency,"wechat_pay","complete","paid"));
            var delivery=due(order,"checkout.session.async_payment_succeeded");assertTrue(service.processNextEvent());verify(inbox).retry(delivery);assertEquals("PENDING",stored(order).get("status"));
        }
        verifyNoInteractions(ledger);
    }

    @Test void strangersCannotQueryOrResumeCnyOrderAndUnknownSupplierResultDoesNotBecomePaid(){
        var owner=owner();String order=order(service.create(owner,"starter-CNY-wechat_pay","wechat_pay"));var stranger=owner();clearInvocations(gateway);
        assertEquals(404,assertThrows(ResponseStatusException.class,()->service.order(stranger,order)).getStatusCode().value());assertEquals(404,assertThrows(ResponseStatusException.class,()->service.resume(stranger,order)).getStatusCode().value());verify(gateway,never()).retrieveSession(anyString());
        String existingSession=sessionId(order);doThrow(new StripeCheckoutGateway.StripeRemoteException(504)).when(gateway).retrieveSession(existingSession);assertEquals("PENDING",service.order(owner,order).get("status"));verifyNoInteractions(ledger);
    }

    @Test void canaryUsesOnlyTheAuthenticatedOwnerAndKeepsPublicPurchasesClosed(){
        var chosen=owner();var stranger=owner();
        ReflectionTestUtils.setField(service,"acceptingNewOrders",false);
        ReflectionTestUtils.setField(service,"canaryOwnerIds",chosen.id());
        assertEquals(false,service.catalog().get("enabled"));
        assertEquals(false,service.catalog(stranger).get("enabled"));
        assertEquals(true,new StripePaymentController(service).catalog(chosen).get("enabled"));
        assertEquals(503,assertThrows(ResponseStatusException.class,()->service.create(stranger,"starter-USD","card")).getStatusCode().value());
        assertEquals(503,assertThrows(ResponseStatusException.class,()->service.create(null,"starter-USD","card")).getStatusCode().value());
        assertEquals(0,number("SELECT COUNT(*) FROM payment_order"));
        String id=order(service.create(chosen,"starter-USD","apple_pay"));
        assertEquals(chosen.id(),stored(id).get("creator_id"));
        assertEquals(new BigDecimal("9.90"),stored(id).get("amount"));
        assertEquals(1,creates.size());
        assertEquals("card",creates.get(0).method());
    }

    @Test void malformedOrOversizedCanaryListsFailClosedAndNeverContactTheProvider(){
        var chosen=owner();ReflectionTestUtils.setField(service,"acceptingNewOrders",false);
        for(String config:List.of("",chosen.id()+",",chosen.id()+",not-a-uuid",chosen.id()+","+UUID.randomUUID()+","+UUID.randomUUID()+","+UUID.randomUUID())){
            ReflectionTestUtils.setField(service,"canaryOwnerIds",config);
            assertEquals(false,service.catalog(chosen).get("enabled"));
            assertEquals(503,assertThrows(ResponseStatusException.class,()->service.create(chosen,"starter-USD","card")).getStatusCode().value());
        }
        assertEquals(0,number("SELECT COUNT(*) FROM payment_order"));assertTrue(creates.isEmpty());
        verify(gateway,never()).verifyNewPurchase(anyString(),anyString());
        ReflectionTestUtils.setField(service,"canaryOwnerIds",chosen.id());
        ReflectionTestUtils.setField(service,"enabled",false);
        assertEquals(false,service.catalog(chosen).get("enabled"));
        assertEquals(503,assertThrows(ResponseStatusException.class,()->service.create(chosen,"starter-USD","card")).getStatusCode().value());
    }

    @Test void readOnlyHistoricalPendingOrderIsPreservedWithoutBlockingANewStripeOrder(){
        var chosen=owner();String old=UUID.randomUUID().toString();
        db.execute("CREATE TABLE p0_credit_cutover_state(id INT PRIMARY KEY,status VARCHAR(20),activated_at TIMESTAMP(6))");
        db.execute("INSERT INTO p0_credit_cutover_state VALUES(1,'ACTIVE',TIMESTAMPADD(MINUTE,-1,CURRENT_TIMESTAMP))");
        db.update("INSERT INTO payment_order(id,creator_id,package_id,amount,currency,credits,status,created_at,provider) VALUES(?,?,'starter-USD',9.90,'USD',100,'PENDING',TIMESTAMPADD(HOUR,-1,CURRENT_TIMESTAMP),'PAYPAL_LIVE')",old,chosen.id());
        ReflectionTestUtils.setField(service,"legacyOrdersReadOnly",true);
        String fresh=order(service.create(chosen,"starter-USD","card"));
        assertEquals("PENDING",stored(old).get("status"));assertNull(stored(old).get("provider_capture_id"));
        assertEquals(chosen.id(),stored(fresh).get("creator_id"));
        assertEquals(409,assertThrows(ResponseStatusException.class,()->service.create(chosen,"starter-USD","card")).getStatusCode().value());
        assertEquals(2,number("SELECT COUNT(*) FROM payment_order"));assertEquals(1,creates.size());
    }

    @Test void cnyRefundUsesOriginalOrderAndExactMinorUnitsWhilePendingOrWrongCurrencyNeverDeduct(){
        var owner=owner();String order=order(service.create(owner,"starter-CNY-wechat_pay","wechat_pay"));fact(order,"complete","paid");service.order(owner,order);clearInvocations(ledger);
        String intent=(String)stored(order).get("provider_capture_id"),refund="re_walletfixture12345678";
        when(gateway.retrieveRefund(refund)).thenReturn(json.valueToTree(Map.of("id",refund,"status","pending","payment_intent",intent,"amount",1376,"currency","cny")));
        StripePaymentService.Resolution pending=ReflectionTestUtils.invokeMethod(service,"reconcileRefund",refund);assertTrue(pending.pending());pending.apply().run();verifyNoInteractions(ledger);
        when(gateway.retrieveRefund(refund)).thenReturn(json.valueToTree(Map.of("id",refund,"status","succeeded","payment_intent",intent,"amount",1376,"currency","usd")));
        assertThrows(IllegalStateException.class,()->ReflectionTestUtils.invokeMethod(service,"reconcileRefund",refund));verifyNoInteractions(ledger);
        when(gateway.retrieveRefund(refund)).thenReturn(json.valueToTree(Map.of("id",refund,"status","succeeded","payment_intent",intent,"amount",1376,"currency","cny")));
        StripePaymentService.Resolution success=ReflectionTestUtils.invokeMethod(service,"reconcileRefund",refund);assertFalse(success.pending());success.apply().run();
        verify(ledger).paymentRefund("STRIPE",MERCHANT,"sandbox",refund,owner.id(),order,intent,1376L,"CNY");
        verify(ledger,never()).credit(anyString(),anyLong(),anyString());assertEquals(new BigDecimal("68.80"),stored(order).get("amount"));
    }

    private StripePaymentService acceptanceService(CreatorPrincipal chosen,String expiry){return acceptanceService(chosen,expiry,"CNY");}
    private StripePaymentService acceptanceService(CreatorPrincipal chosen,String expiry,String currency){
        var env=walletEnvironment("GB",true).withProperty("qingfeng.payment.acceptance.wechat-currency",currency).withProperty("qingfeng.payment.acceptance.enabled","true")
            .withProperty("qingfeng.payment.acceptance.owner-id",chosen.id()).withProperty("qingfeng.payment.acceptance.nonce","abcdef01")
            .withProperty("qingfeng.payment.acceptance.expires-at",expiry);
        var result=service(env);ReflectionTestUtils.setField(result,"acceptingNewOrders",false);ReflectionTestUtils.setField(result,"canaryOwnerIds",chosen.id());return result;
    }
    private static String acceptanceId(String channel){return "accept-abcdef01-"+channel+("wechat_pay".equals(channel)?"-CNY":"-USD");}

    @Test void privateAcceptanceIsAbsentForPublicStrangerAndExpiredOwnerAndCannotBuyOrdinarySku(){
        var chosen=owner();service=acceptanceService(chosen,"2100-01-01T00:00:00Z");
        assertFalse(service.catalog().toString().contains("accept-"));assertFalse(service.catalog(owner()).toString().contains("accept-"));
        assertTrue(service.catalog(chosen).toString().contains(acceptanceId("card")));
        assertFalse(service.catalog(chosen).toString().contains("starter-USD"));
        assertThrows(ResponseStatusException.class,()->service.create(chosen,"starter-USD","card"));
        assertThrows(ResponseStatusException.class,()->service.create(null,acceptanceId("card"),"card"));
        var expired=acceptanceService(chosen,"2000-01-01T00:00:00Z");assertFalse(expired.catalog(chosen).toString().contains("accept-"));
        assertThrows(ResponseStatusException.class,()->expired.create(chosen,acceptanceId("card"),"card"));
        assertThrows(ResponseStatusException.class,()->expired.create(chosen,"starter-USD","card"));
        assertEquals(0,number("SELECT COUNT(*) FROM payment_order"));assertTrue(creates.isEmpty());
    }
    @Test void everyPrivateChannelHasAnIndependentImmutableOneCreditSnapshotAndFailedNeverReleasesItsSlot(){
        var chosen=owner();service=acceptanceService(chosen,"2100-01-01T00:00:00Z");
        for(String channel:List.of("card","apple_pay","wechat_pay")){
            String id=order(service.create(chosen,acceptanceId(channel),channel));var saved=stored(id);
            assertEquals(1,((Number)saved.get("credits")).longValue());assertEquals(new BigDecimal("wechat_pay".equals(channel)?"3.00":"0.50"),saved.get("amount"));
            db.update("UPDATE payment_order SET status='FAILED' WHERE id=?",id);
            assertThrows(ResponseStatusException.class,()->service.create(chosen,acceptanceId(channel),channel));
            assertFalse(service.catalog(chosen).toString().contains(acceptanceId(channel)));
        }
        assertEquals(3,number("SELECT COUNT(*) FROM payment_order"));assertEquals(3,creates.size());verifyNoInteractions(ledger);
    }
    @Test void concurrentPrivateCreatesReserveOneOrderUnderTheExistingCreatorRowLock()throws Exception{
        var chosen=owner();service=acceptanceService(chosen,"2100-01-01T00:00:00Z");
        var start=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.Callable<Boolean> task=()->{start.await();try{service.create(chosen,acceptanceId("card"),"card");return true;}catch(ResponseStatusException duplicate){assertEquals(409,duplicate.getStatusCode().value());return false;}};
        try{var a=pool.submit(task);var b=pool.submit(task);start.countDown();assertEquals(1,(a.get()?1:0)+(b.get()?1:0));}finally{pool.shutdownNow();}
        assertEquals(1,number("SELECT COUNT(*) FROM payment_order"));assertEquals(1,creates.size());verifyNoInteractions(ledger);
    }
    @Test void latePaidAndRefundUseOriginalPrivateSnapshotAfterAcceptanceIsExpiredOrDisabled(){
        var chosen=owner();service=acceptanceService(chosen,"2100-01-01T00:00:00Z");
        String id=order(service.create(chosen,acceptanceId("wechat_pay"),"wechat_pay"));fact(id,"complete","paid");
        service=acceptanceService(chosen,"2000-01-01T00:00:00Z");assertEquals("PAID",service.order(chosen,id).get("status"));
        verify(ledger).credit(chosen.id(),1L,"payment:"+id);clearInvocations(ledger);
        // The next deployment can remove the entire acceptance configuration.
        service=service(walletEnvironment("GB",false));String intent=(String)stored(id).get("provider_capture_id"),refund="re_acceptance12345678";
        when(gateway.retrieveRefund(refund)).thenReturn(json.valueToTree(Map.of("id",refund,"status","succeeded","payment_intent",intent,"amount",300,"currency","cny")));
        StripePaymentService.Resolution resolution=ReflectionTestUtils.invokeMethod(service,"reconcileRefund",refund);resolution.apply().run();
        verify(ledger).paymentRefund("STRIPE",MERCHANT,"sandbox",refund,chosen.id(),id,intent,300L,"CNY");
        assertEquals(new BigDecimal("3.00"),stored(id).get("amount"));assertEquals(1,((Number)stored(id).get("credits")).longValue());
    }
    @Test void publicPurchasesKeepOrdinaryUsersUnchangedWhileOnlyTheActiveOwnerGetsTemporaryQuotes(){
        var chosen=owner();var ordinary=owner();var normalCatalog=service.catalog(ordinary);
        service=acceptanceService(chosen,"2100-01-01T00:00:00Z");ReflectionTestUtils.setField(service,"acceptingNewOrders",true);
        assertEquals(normalCatalog,service.catalog(ordinary));assertFalse(service.catalog().toString().contains("accept-"));
        assertTrue(service.catalog(chosen).toString().contains(acceptanceId("card")));assertFalse(service.catalog(chosen).toString().contains("starter-USD"));
        assertEquals(403,assertThrows(ResponseStatusException.class,()->service.create(ordinary,acceptanceId("card"),"card")).getStatusCode().value());
        assertThrows(ResponseStatusException.class,()->service.create(chosen,"starter-USD","card"));assertEquals(0,number("SELECT COUNT(*) FROM payment_order"));
        var normal=stored(order(service.create(ordinary,"starter-USD","card")));assertEquals(new BigDecimal("9.90"),normal.get("amount"));assertEquals(100,((Number)normal.get("credits")).longValue());
        var temporary=stored(order(service.create(chosen,acceptanceId("card"),"card")));assertEquals(new BigDecimal("0.50"),temporary.get("amount"));assertEquals(1,((Number)temporary.get("credits")).longValue());
        verifyNoInteractions(ledger);
    }
    @Test void publicGbpOwnerHasOnlyOneMatchingCurrencyAndExpiryRestoresTheNormalCatalog(){
        var chosen=owner();service=acceptanceService(chosen,"2100-01-01T00:00:00Z","GBP");ReflectionTestUtils.setField(service,"acceptingNewOrders",true);
        @SuppressWarnings("unchecked") var methods=(List<Map<String,Object>>)service.catalog(chosen).get("paymentMethods");
        var wechat=methods.stream().filter(m->"wechat_pay".equals(m.get("id"))).findFirst().orElseThrow();
        assertEquals(List.of("GBP"),wechat.get("currencies"));assertEquals(List.of(Map.of("currency","GBP","enabled",true,"reason","")),wechat.get("currencyOptions"));
        String offer="accept-abcdef01-wechat_pay-GBP";
        for(String invalid:List.of(acceptanceId("wechat_pay"),"accept-abcdef02-wechat_pay-GBP","accept-abcdef01-card-GBP"))
            assertThrows(ResponseStatusException.class,()->service.create(chosen,invalid,"wechat_pay"));
        assertThrows(ResponseStatusException.class,()->service.create(chosen,offer,"card"));assertEquals(0,number("SELECT COUNT(*) FROM payment_order"));
        String id=order(service.create(chosen,offer,"wechat_pay"));assertEquals(new BigDecimal("0.50"),stored(id).get("amount"));assertEquals("GBP",stored(id).get("currency"));
        fact(id,"complete","paid");service=service(walletEnvironment("GB",false));assertEquals("PAID",service.order(chosen,id).get("status"));verify(ledger).credit(chosen.id(),1L,"payment:"+id);
        service=acceptanceService(chosen,"2000-01-01T00:00:00Z","GBP");ReflectionTestUtils.setField(service,"acceptingNewOrders",true);
        assertFalse(service.catalog(chosen).toString().contains("accept-"));assertTrue(service.catalog(chosen).toString().contains("starter-USD"));
        assertThrows(ResponseStatusException.class,()->service.create(chosen,offer,"wechat_pay"));
        var normal=stored(order(service.create(chosen,"starter-USD","card")));assertEquals(new BigDecimal("9.90"),normal.get("amount"));
    }

    @Test void gbpPrivateQuoteCannotInventSupportForAnUnsupportedMerchantCountry(){
        var chosen=owner();var env=walletEnvironment("US",true).withProperty("qingfeng.payment.acceptance.enabled","true")
            .withProperty("qingfeng.payment.acceptance.owner-id",chosen.id()).withProperty("qingfeng.payment.acceptance.nonce","abcdef01")
            .withProperty("qingfeng.payment.acceptance.wechat-currency","GBP").withProperty("qingfeng.payment.acceptance.expires-at","2100-01-01T00:00:00Z");
        service=service(env);assertFalse(service.catalog(chosen).toString().contains("accept-abcdef01-wechat_pay-GBP"));
        assertThrows(ResponseStatusException.class,()->service.create(chosen,"accept-abcdef01-wechat_pay-GBP","wechat_pay"));
        assertEquals(0,number("SELECT COUNT(*) FROM payment_order"));assertTrue(creates.isEmpty());
    }

    private PayPalPaymentService paypalAcceptance(CreatorPrincipal chosen,String expiry,PayPalCheckoutGateway remote){
        db.execute("CREATE TABLE IF NOT EXISTS payment_risk_case(id VARCHAR(36),order_id VARCHAR(36),owner_id VARCHAR(36),provider VARCHAR(32),status VARCHAR(20))");
        var env=walletEnvironment("GB",true).withProperty("qingfeng.payment.acceptance.enabled","true")
            .withProperty("qingfeng.payment.acceptance.owner-id",chosen.id()).withProperty("qingfeng.payment.acceptance.nonce","abcdef01")
            .withProperty("qingfeng.payment.acceptance.expires-at",expiry);
        var result=new PayPalPaymentService(db,tx,ledger,new PaymentCatalog(env),env){@Override PayPalCheckoutGateway gateway(){return remote;}};
        for(var entry:Map.<String,Object>of("enabled",true,"acceptingNewOrders",false,"canaryOwnerIds",chosen.id(),"environment","sandbox", "clientId","synthetic-client","clientSecret","synthetic-secret","merchantId",MERCHANT,"webhookId","synthetic-webhook","returnBase","http://127.0.0.1/account").entrySet())
            ReflectionTestUtils.setField(result,entry.getKey(),entry.getValue());
        return result;
    }
    private PayPalCheckoutGateway paypalRemote(){
        var remote=mock(PayPalCheckoutGateway.class);
        when(remote.create(anyString(),anyString(),any(),anyString(),anyString(),anyString()))
            .thenAnswer(call->new PayPalCheckoutGateway.Created("PAYPAL_"+call.getArgument(0),"https://www.sandbox.paypal.com/checkoutnow?token=synthetic"));
        return remote;
    }

    @Test void paypalPrivatePriceIsOwnerOnlyAndNormalAndFailedSkuCannotBeUsedForExtraPayments(){
        var chosen=owner();var remote=paypalRemote();var paypal=paypalAcceptance(chosen,"2100-01-01T00:00:00Z",remote);
        assertFalse(paypal.catalog().toString().contains("accept-"));assertFalse(paypal.catalog(owner()).toString().contains("accept-"));
        assertTrue(paypal.catalog(chosen).toString().contains(acceptanceId("paypal")));assertFalse(paypal.catalog(chosen).toString().contains("starter-USD"));
        assertThrows(ResponseStatusException.class,()->paypal.create(chosen.id(),"starter-USD"));
        assertThrows(ResponseStatusException.class,()->paypal.create(owner().id(),acceptanceId("paypal")));
        String id=order(paypal.create(chosen.id(),acceptanceId("paypal")));assertEquals(new BigDecimal("0.01"),stored(id).get("amount"));assertEquals(1,((Number)stored(id).get("credits")).longValue());
        db.update("UPDATE payment_order SET status='FAILED' WHERE id=?",id);
        assertThrows(ResponseStatusException.class,()->paypal.create(chosen.id(),acceptanceId("paypal")));assertFalse(paypal.catalog(chosen).toString().contains("accept-"));
        verify(remote,times(1)).create(anyString(),anyString(),eq(new BigDecimal("0.01")),eq("USD"),anyString(),anyString());verifyNoInteractions(ledger);
    }
    @Test void paypalPrivateConcurrentCreatesShareTheSameCreatorLockAndOnePermanentSlot()throws Exception{
        var chosen=owner();var remote=paypalRemote();var paypal=paypalAcceptance(chosen,"2100-01-01T00:00:00Z",remote);
        var start=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.Callable<Boolean> task=()->{start.await();try{paypal.create(chosen.id(),acceptanceId("paypal"));return true;}catch(ResponseStatusException duplicate){assertEquals(409,duplicate.getStatusCode().value());return false;}};
        try{var a=pool.submit(task);var b=pool.submit(task);start.countDown();assertEquals(1,(a.get()?1:0)+(b.get()?1:0));}finally{pool.shutdownNow();}
        assertEquals(1,number("SELECT COUNT(*) FROM payment_order"));verify(remote,times(1)).create(anyString(),anyString(),any(),anyString(),anyString(),anyString());verifyNoInteractions(ledger);
    }
    @Test void paypalLateCompletedCaptureKeepsTheOriginalCentAndOneCreditSnapshotAfterExpiry(){
        var chosen=owner();var remote=paypalRemote();var paypal=paypalAcceptance(chosen,"2100-01-01T00:00:00Z",remote);
        String id=order(paypal.create(chosen.id(),acceptanceId("paypal")));String linked=(String)stored(id).get("provider_link_id");
        var expired=paypalAcceptance(chosen,"2000-01-01T00:00:00Z",remote);
        assertThrows(ResponseStatusException.class,()->expired.create(chosen.id(),acceptanceId("paypal")));
        var wrong=new PayPalCheckoutGateway.Snapshot(linked,id,"COMPLETED",MERCHANT,"CAP_ACCEPT1","COMPLETED",new BigDecimal("0.02"),"USD");
        assertThrows(ResponseStatusException.class,()->expired.settle(id,wrong));verifyNoInteractions(ledger);
        var valid=new PayPalCheckoutGateway.Snapshot(linked,id,"COMPLETED",MERCHANT,"CAP_ACCEPT1","COMPLETED",new BigDecimal("0.01"),"USD");
        expired.settle(id,valid);assertEquals("PAID",stored(id).get("status"));verify(ledger).credit(chosen.id(),1L,"payment:"+id);
        assertEquals(new BigDecimal("0.01"),stored(id).get("amount"));assertEquals(1,((Number)stored(id).get("credits")).longValue());
    }

    @Test void paypalExcludesOnlyTheOrdersAlreadyIsolatedByAnActiveLegacyReadOnlyBoundary(){
        var chosen=owner();var remote=paypalRemote();var paypal=paypalAcceptance(chosen,"2100-01-01T00:00:00Z",remote);
        db.execute("CREATE TABLE p0_credit_cutover_state(id INT PRIMARY KEY,status VARCHAR(20),activated_at TIMESTAMP(6))");
        db.execute("INSERT INTO p0_credit_cutover_state VALUES(1,'ACTIVE',TIMESTAMPADD(MINUTE,-1,CURRENT_TIMESTAMP))");
        var oldOrders=new ArrayList<String>();
        for(String status:List.of("PENDING","CREATION_UNKNOWN")){
            String id=UUID.randomUUID().toString();oldOrders.add(id);
            db.update("INSERT INTO payment_order(id,creator_id,package_id,amount,currency,credits,status,created_at,provider) VALUES(?,?,'starter-USD',9.90,'USD',100,?,TIMESTAMPADD(HOUR,-1,CURRENT_TIMESTAMP),'PAYPAL_SANDBOX')",id,chosen.id(),status);
        }
        assertThrows(ResponseStatusException.class,()->paypal.create(chosen.id(),acceptanceId("paypal")));verifyNoInteractions(remote);
        ReflectionTestUtils.setField(paypal,"legacyOrdersReadOnly",true);
        String fresh=order(paypal.create(chosen.id(),acceptanceId("paypal")));
        assertEquals("PENDING",stored(oldOrders.get(0)).get("status"));assertEquals("CREATION_UNKNOWN",stored(oldOrders.get(1)).get("status"));
        for(String old:oldOrders){assertNull(stored(old).get("provider_capture_id"));assertNull(stored(old).get("provider_link_id"));}
        assertEquals("PENDING",stored(fresh).get("status"));assertEquals(3,number("SELECT COUNT(*) FROM payment_order"));
        assertThrows(ResponseStatusException.class,()->paypal.create(chosen.id(),acceptanceId("paypal")));verify(remote,times(1)).create(anyString(),anyString(),any(),anyString(),anyString(),anyString());verifyNoInteractions(ledger);
    }
    @Test void paypalCannotIgnoreAnUnknownOrderWithoutAConfirmedActiveCutoverOrAfterTheBoundary(){
        var chosen=owner();var remote=paypalRemote();var paypal=paypalAcceptance(chosen,"2100-01-01T00:00:00Z",remote);
        db.execute("CREATE TABLE p0_credit_cutover_state(id INT PRIMARY KEY,status VARCHAR(20),activated_at TIMESTAMP(6))");
        db.execute("INSERT INTO p0_credit_cutover_state VALUES(1,'PREPARING',NULL)");ReflectionTestUtils.setField(paypal,"legacyOrdersReadOnly",true);
        assertEquals(503,assertThrows(ResponseStatusException.class,()->paypal.create(chosen.id(),acceptanceId("paypal"))).getStatusCode().value());
        db.execute("UPDATE p0_credit_cutover_state SET status='ACTIVE',activated_at=TIMESTAMPADD(MINUTE,-1,CURRENT_TIMESTAMP)");
        db.update("INSERT INTO payment_order(id,creator_id,package_id,amount,currency,credits,status,created_at,provider) VALUES(?,?,'starter-USD',9.90,'USD',100,'CREATION_UNKNOWN',CURRENT_TIMESTAMP,'PAYPAL_SANDBOX')",UUID.randomUUID().toString(),chosen.id());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->paypal.create(chosen.id(),acceptanceId("paypal"))).getStatusCode().value());verifyNoInteractions(remote);verifyNoInteractions(ledger);
    }
    @Test void paypalPublicPurchasesRemainNormalForOthersAndOwnerQuoteExpiresIndependently(){
        var chosen=owner();var ordinary=owner();var remote=paypalRemote();var paypal=paypalAcceptance(chosen,"2100-01-01T00:00:00Z",remote);
        ReflectionTestUtils.setField(paypal,"acceptingNewOrders",true);
        assertTrue(paypal.catalog(ordinary).toString().contains("starter-USD"));assertFalse(paypal.catalog(ordinary).toString().contains("accept-"));
        assertTrue(paypal.catalog(chosen).toString().contains(acceptanceId("paypal")));assertFalse(paypal.catalog(chosen).toString().contains("starter-USD"));
        assertThrows(ResponseStatusException.class,()->paypal.create(ordinary.id(),acceptanceId("paypal")));
        assertThrows(ResponseStatusException.class,()->paypal.create(chosen.id(),"starter-USD"));assertEquals(0,number("SELECT COUNT(*) FROM payment_order"));
        String normal=order(paypal.create(ordinary.id(),"starter-USD"));assertEquals(new BigDecimal("9.90"),stored(normal).get("amount"));assertEquals(100,((Number)stored(normal).get("credits")).longValue());
        String temporary=order(paypal.create(chosen.id(),acceptanceId("paypal")));assertEquals(new BigDecimal("0.01"),stored(temporary).get("amount"));assertEquals(1,((Number)stored(temporary).get("credits")).longValue());
        for(String status:List.of("CANCELLED","FAILED","CREATION_UNKNOWN","PAID")){
            db.update("UPDATE payment_order SET status=? WHERE id=?",status,temporary);
            assertThrows(ResponseStatusException.class,()->paypal.create(chosen.id(),acceptanceId("paypal")));
        }
        var expired=paypalAcceptance(chosen,"2000-01-01T00:00:00Z",remote);ReflectionTestUtils.setField(expired,"acceptingNewOrders",true);
        assertFalse(expired.catalog(chosen).toString().contains("accept-"));assertTrue(expired.catalog(chosen).toString().contains("starter-USD"));
        assertThrows(ResponseStatusException.class,()->expired.create(chosen.id(),acceptanceId("paypal")));
        assertEquals(new BigDecimal("9.90"),stored(order(expired.create(chosen.id(),"starter-USD"))).get("amount"));
        verifyNoInteractions(ledger);
    }

}
