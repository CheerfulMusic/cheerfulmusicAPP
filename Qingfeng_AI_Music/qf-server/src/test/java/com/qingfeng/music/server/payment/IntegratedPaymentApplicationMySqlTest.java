package com.qingfeng.music.server.payment;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.credits.CreditsLedger;
import com.qingfeng.music.credits.CreditsPricing;
import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.infrastructure.persistence.credits.JdbcFencedCreditsLedger;
import com.qingfeng.music.infrastructure.persistence.usage.JdbcUsageBudget;
import com.qingfeng.music.server.MusicApplication;
import com.qingfeng.music.server.avatar.AvatarGenerationService;
import com.qingfeng.music.server.avatar.AvatarImageProvider;
import com.qingfeng.music.server.avatar.AvatarService;
import com.qingfeng.music.server.config.CreditSchemaReadiness;
import com.qingfeng.music.usage.UsageBudget;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.core.env.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real full Boot/V1..V40/MySQL/HTTP/SDK-signature integration with synthetic supplier facts.
 * Not Sandbox/Live, real historical migration or real AI/media execution. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IntegratedPaymentApplicationMySqlTest {
    static final String PREFIX="P0_INTEGRATION_APPLICATION_MYSQL_",ACCOUNT="acct_offline12345678",PAYPAL_MERCHANT="TESTMERCHANT";
    static final String SECRET="whsec_integrated_offline_fixture_not_a_real_credential";
    static final ObjectMapper JSON=new ObjectMapper();
    static final Map<String,JsonNode> SESSIONS=new ConcurrentHashMap<>(),REFUNDS=new ConcurrentHashMap<>(),DISPUTES=new ConcurrentHashMap<>(),CHARGES=new ConcurrentHashMap<>(),BALANCES=new ConcurrentHashMap<>();
    static final Map<String,PayPalCheckoutGateway.Snapshot> PAYPAL_ORDERS=new ConcurrentHashMap<>();
    static final Set<String> OWNERS=ConcurrentHashMap.newKeySet();
    static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    static ConfigurableApplicationContext application;
    static JdbcTemplate db;static CreditsLedger ledger;static StripePaymentService stripe;static PayPalPaymentService paypal;static PaymentInbox inbox;
    static IdentityApi identity;static JdbcUsageBudget usage;static PlatformTransactionManager manager;
    static String database,url,user,password,legacyOwner;static int port;static Map<String,Object> settings;
    record Paid(IdentityApi.Session owner,String order,String session,String intent){}
    static String required(String suffix){String value=System.getenv(PREFIX+suffix);if(value==null||value.isBlank())throw new IllegalStateException("Explicit isolated application resource required: "+PREFIX+suffix);return value;}
    static String unique(){return UUID.randomUUID().toString().replace("-","");}
    static long number(String sql,Object... args){return Objects.requireNonNull(db.queryForObject(sql,Long.class,args));}
    static JsonNode known(Map<String,JsonNode> facts,String id){var fact=facts.get(id);if(fact==null)throw new AssertionError("Unregistered synthetic supplier resource; external API forbidden");return fact;}

    @BeforeAll static void proveMigrateMapActivateThenStartFullApplication() throws Exception {
        database=required("DATABASE");url=required("URL");user=required("USER");password=required("PASSWORD");
        if(!database.matches("p0_integration_application_20261112r[0-9]+")||!url.matches("jdbc:mysql://127\\.0\\.0\\.1:23316/"+database+"(?:\\?.*)?")
            ||"root".equalsIgnoreCase(user)||url.toLowerCase(Locale.ROOT).matches(".*(?:password=|user=|socketfactory|dnssrv|loadbalance|address=).*"))
            throw new IllegalStateException("Only a new independently scoped loopback MySQL cohort is permitted");
        var source=new DriverManagerDataSource(url,user,password);db=new JdbcTemplate(source);
        try(var connection=source.getConnection();var statement=connection.createStatement()){
            try(var facts=statement.executeQuery("SELECT VERSION(),@@server_uuid,@@datadir,@@port,@@bind_address,DATABASE(),CURRENT_ROLE(),@@transaction_isolation")){
                assertTrue(facts.next());assertEquals("8.0.46",facts.getString(1));assertEquals("05c27d3c-dda3-11f1-aba0-84a938421519",facts.getString(2));assertEquals(required("UUID"),facts.getString(2));
                assertEquals("r:/data/",facts.getString(3).replace('\\','/').toLowerCase(Locale.ROOT));assertEquals(23316,facts.getInt(4));assertEquals("127.0.0.1",facts.getString(5));
                assertEquals(database,facts.getString(6));assertEquals("NONE",facts.getString(7));assertEquals("REPEATABLE-READ",facts.getString(8));
            }
            int grants=0;try(var facts=statement.executeQuery("SHOW GRANTS")){while(facts.next()){
                String grant=facts.getString(1);assertTrue(grant.startsWith("GRANT USAGE ON *.*")||grant.contains("ON `"+database+"`.*"));assertFalse(grant.contains("WITH GRANT OPTION"));grants++;
            }}assertEquals(2,grants);
            try(var facts=statement.executeQuery("SELECT SCHEMA_NAME FROM information_schema.SCHEMATA")){while(facts.next())assertTrue(Set.of(database,"information_schema","performance_schema").contains(facts.getString(1)));}
        }
        assertEquals(0,number("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()"),"Never clear or reuse a previous acceptance cohort");
        Flyway.configure().dataSource(source).locations("classpath:db/migration").cleanDisabled(true).target("39").load().migrate();
        legacyOwner=UUID.randomUUID().toString();OWNERS.add(legacyOwner);
        db.update("INSERT INTO creator(id,created_at) VALUES(?,CURRENT_TIMESTAMP)",legacyOwner);
        db.update("INSERT INTO credit_ledger VALUES('CREDIT','synthetic-legacy-gift',?,100,CURRENT_TIMESTAMP)",legacyOwner);
        db.update("INSERT INTO credit_ledger VALUES('DEBIT','synthetic-legacy-debit',?,-25,CURRENT_TIMESTAMP)",legacyOwner);
        db.update("INSERT INTO credit_account(owner_id,balance,updated_at) VALUES(?,75,CURRENT_TIMESTAMP)",legacyOwner);
        Flyway.configure().dataSource(source).locations("classpath:db/migration").cleanDisabled(true).target("40").load().migrate();
        try(var resource=Objects.requireNonNull(IntegratedPaymentApplicationMySqlTest.class.getResourceAsStream("/credit-migration-reference.json"))){
            var manifest=JSON.readTree(resource);for(var m:manifest.path("core"))assertHistory(m);assertHistory(manifest.path("integration"));
        }
        // Synthetic opening balance does not invent historical FIFO allocations.
        db.update("INSERT INTO p0_credit_batch(reference,owner_id,source_type,source_id,original,consumed,adjusted,held,status,created_at) VALUES(?,?,'LEGACY_UNATTRIBUTED','SYNTHETIC_APPLICATION_FIXTURE',75,0,0,0,'ACTIVE',CURRENT_TIMESTAMP)","legacy-opening:"+legacyOwner,legacyOwner);
        db.update("INSERT INTO p0_legacy_debit_mapping VALUES('synthetic-legacy-debit',?,25,0,'UNKNOWN')",legacyOwner);
        assertEquals(75,number("SELECT SUM(amount) FROM credit_ledger"));assertEquals(75,number("SELECT SUM(original-consumed-adjusted) FROM p0_credit_batch"));
        assertEquals(1,db.update("UPDATE p0_credit_cutover_state SET status='ACTIVE',activated_at=CURRENT_TIMESTAMP,evidence='SYNTHETIC_APPLICATION_MAPPING_NO_PRODUCTION_DATA' WHERE id=1 AND status='PREPARING'"));
        CreditSchemaReadiness.requireActive(db);settings=configuration();start();
    }
    static void assertHistory(JsonNode expected){var row=db.queryForMap("SELECT script,checksum,success FROM flyway_schema_history WHERE version=?",expected.path("version").asString());assertEquals(expected.path("script").asString(),row.get("script"));assertEquals(expected.path("checksum").asInt(),((Number)row.get("checksum")).intValue());assertTrue(Boolean.TRUE.equals(row.get("success"))||row.get("success") instanceof Number n&&n.intValue()==1);}
    static Map<String,Object> configuration(){
        Map<String,Object> values=new LinkedHashMap<>();
        values.put("spring.datasource.url",url);values.put("spring.datasource.username",user);values.put("spring.datasource.password",password);values.put("spring.datasource.driver-class-name","com.mysql.cj.jdbc.Driver");
        values.put("spring.datasource.hikari.maximum-pool-size",16);values.put("spring.datasource.hikari.connection-init-sql","SET SESSION innodb_lock_wait_timeout=5");
        values.put("spring.flyway.target","40");values.put("spring.flyway.clean-disabled",true);values.put("spring.sql.init.mode","never");values.put("server.address","127.0.0.1");values.put("server.port",0);
        values.put("qingfeng.credits.writer-fence.enabled",true);values.put("qingfeng.credits.writer-fence.expected-database",database);values.put("qingfeng.credits.writer-fence.expected-port",23316);values.put("qingfeng.credits.enabled",true);
        values.put("qingfeng.stripe.enabled",true);values.put("qingfeng.stripe.accepting-new-orders",true);values.put("qingfeng.stripe.environment","sandbox");values.put("qingfeng.stripe.account-id",ACCOUNT);values.put("qingfeng.stripe.api-key","sk_test_integrated_offline_fixture");values.put("qingfeng.stripe.webhook-secret",SECRET);
        values.put("qingfeng.paypal.enabled",true);values.put("qingfeng.paypal.environment","sandbox");values.put("qingfeng.paypal.client-id","offline-fixture");values.put("qingfeng.paypal.client-secret","offline-fixture");values.put("qingfeng.paypal.merchant-id",PAYPAL_MERCHANT);values.put("qingfeng.paypal.webhook-id","offline-fixture");
        for(String key:List.of("qingfeng.payment.inbox.scheduler-enabled","qingfeng.events.dispatcher-enabled","qingfeng.events.rabbit.enabled","qingfeng.redis.enabled","qingfeng.generation.enabled","qingfeng.generation.scheduler-enabled","qingfeng.cover.ai.enabled","qingfeng.cover.ai.scheduler-enabled","qingfeng.analytics.retention.scheduler-enabled","qingfeng.quick.enabled","qingfeng.invite-gate.enabled","qingfeng.payments.enabled","qingfeng.agent.chat.enabled","qingfeng.auth.google.enabled","qingfeng.auth.logto.enabled","qingfeng.reference.analysis.enabled","qingfeng.music-settings.simplify.enabled","qingfeng.lyrics.timing.enabled","qingfeng.inspiration.rewrite.enabled"))values.put(key,false);
        values.put("qingfeng.avatar.ai.enabled",false);values.put("qingfeng.avatar.ai.scheduler-enabled",false);
        values.put("qingfeng.cover.directory","./target/integrated-payment-covers");values.put("qingfeng.media.directory","./target/integrated-payment-audio");return values;
    }
    static void start(){
        var environment=new StandardEnvironment();environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("isolated-application-acceptance-only",settings));
        application=new SpringApplicationBuilder(MusicApplication.class,OfflineGateways.class).environment(environment).profiles("mysql").web(WebApplicationType.SERVLET).logStartupInfo(false)
            .initializers(context->context.getBeanFactory().registerSingleton("acceptanceOnlyTestConfigurationFilter",new AcceptanceOnlyTestFilter())).run();
        db=application.getBean(JdbcTemplate.class);ledger=application.getBean(CreditsLedger.class);stripe=application.getBean(StripePaymentService.class);paypal=application.getBean(PayPalPaymentService.class);inbox=application.getBean(PaymentInbox.class);identity=application.getBean(IdentityApi.class);usage=application.getBean(JdbcUsageBudget.class);manager=application.getBean(PlatformTransactionManager.class);port=application.getEnvironment().getRequiredProperty("local.server.port",Integer.class);
        assertInstanceOf(JdbcFencedCreditsLedger.class,ledger);assertEquals("40",application.getEnvironment().getProperty("spring.flyway.target"));assertTrue(CreditSchemaReadiness.ready(db));
    }
    @AfterAll static void close(){if(application!=null)application.close();}
    @AfterEach void allFinancialProjectionsRemainEqual(){for(String owner:OWNERS){long book=number("SELECT COALESCE(SUM(amount),0) FROM credit_ledger WHERE owner_id=?",owner);assertEquals(book,number("SELECT COALESCE(SUM(balance),0) FROM credit_account WHERE owner_id=?",owner));assertEquals(book,number("SELECT COALESCE(SUM(CAST(original AS DECIMAL(65,0))-consumed-adjusted),0) FROM p0_credit_batch WHERE owner_id=?",owner));assertEquals(book,ledger.balance(owner));}}

    /** Programmatic Boot must not auto-discover fixtures belonging to unrelated JUnit classes. */
    static final class AcceptanceOnlyTestFilter extends org.springframework.boot.context.TypeExcludeFilter {
        @Override public boolean match(org.springframework.core.type.classreading.MetadataReader reader,org.springframework.core.type.classreading.MetadataReaderFactory factory) throws java.io.IOException {
            return reader.getResource().getURL().getPath().replace('\\','/').contains("/test-classes/");
        }
        @Override public boolean equals(Object other){return other instanceof AcceptanceOnlyTestFilter;}
        @Override public int hashCode(){return AcceptanceOnlyTestFilter.class.hashCode();}
    }

    @TestConfiguration(proxyBeanMethods=false) static class OfflineGateways {
        @Bean @Primary StripeCheckoutGateway offlineStripe(ObjectMapper json){
            var gateway=spy(new StripeCheckoutGateway(json));doNothing().when(gateway).verifyAccount();
            doAnswer(call->{String order=call.getArgument(0),session="cs_test_"+unique();var fact=session(session,order,false);SESSIONS.put(session,fact);return fact;}).when(gateway).createSession(anyString(),anyString(),any(),anyString());
            doAnswer(call->known(SESSIONS,call.getArgument(0))).when(gateway).retrieveSession(anyString());doAnswer(call->known(REFUNDS,call.getArgument(0))).when(gateway).retrieveRefund(anyString());doAnswer(call->known(DISPUTES,call.getArgument(0))).when(gateway).retrieveDispute(anyString());doAnswer(call->known(CHARGES,call.getArgument(0))).when(gateway).retrieveCharge(anyString());doAnswer(call->known(BALANCES,call.getArgument(0))).when(gateway).retrieveBalanceTransaction(anyString());
            doThrow(new AssertionError("Unexpected external refund-list query")).when(gateway).refundsForCharge(anyString());doThrow(new AssertionError("Unexpected external dispute-list query")).when(gateway).disputesForCharge(anyString());return gateway;
        }
        @Bean PayPalCheckoutGateway offlinePayPalGateway(){
            var gateway=mock(PayPalCheckoutGateway.class);when(gateway.verifyWebhook(any(),any())).thenReturn(true);
            when(gateway.create(anyString(),anyString(),any(),anyString(),anyString(),anyString())).thenAnswer(call->{String remote="P"+unique().substring(0,20),order=call.getArgument(0);PAYPAL_ORDERS.put(remote,new PayPalCheckoutGateway.Snapshot(remote,order,"APPROVED",PAYPAL_MERCHANT,"","",new BigDecimal("9.90"),"USD"));return new PayPalCheckoutGateway.Created(remote,"https://www.sandbox.paypal.com/checkoutnow?token="+remote);});
            when(gateway.retrieve(anyString())).thenAnswer(call->{var fact=PAYPAL_ORDERS.get(call.getArgument(0));if(fact==null)throw new AssertionError("Unregistered PayPal resource; external API forbidden");return fact;});
            when(gateway.capture(anyString(),anyString())).thenAnswer(call->{String remote=call.getArgument(0);var prior=PAYPAL_ORDERS.get(remote);if(prior==null)throw new AssertionError("Unknown capture order");var fact=new PayPalCheckoutGateway.Snapshot(remote,prior.reference(),"COMPLETED",PAYPAL_MERCHANT,"C"+unique().substring(0,20),"COMPLETED",new BigDecimal("9.90"),"USD");PAYPAL_ORDERS.put(remote,fact);return fact;});
            when(gateway.retrieveRefund(anyString())).thenThrow(new AssertionError("Unregistered PayPal refund GET"));when(gateway.retrieveDispute(anyString())).thenThrow(new AssertionError("Unregistered PayPal dispute GET"));when(gateway.retrieveCapture(anyString())).thenThrow(new AssertionError("Unregistered PayPal capture GET"));return gateway;
        }
        @Bean @Primary PayPalPaymentService offlinePayPalService(JdbcTemplate db,PlatformTransactionManager manager,CreditsLedger credits,PaymentCatalog catalog,Environment env,PayPalCheckoutGateway offlinePayPalGateway){return new PayPalPaymentService(db,new TransactionTemplate(manager),credits,catalog,env){@Override PayPalCheckoutGateway gateway(){return offlinePayPalGateway;}};}
    }
    static JsonNode session(String id,String order,boolean paid){
        var fields=new LinkedHashMap<String,Object>();fields.put("id",id);fields.put("url","https://checkout.stripe.com/c/pay/"+id+"#offline-fragment");fields.put("status",paid?"complete":"open");fields.put("payment_status",paid?"paid":"unpaid");fields.put("payment_intent","pi_"+id.substring(8));fields.put("metadata",Map.of("order_id",order));fields.put("client_reference_id",order);fields.put("mode","payment");fields.put("amount_total",990);fields.put("currency","usd");fields.put("livemode",false);return JSON.valueToTree(fields);
    }
    static HttpResponse<String> request(String path,String method,String body,String cookie,String csrf) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(25));if(cookie!=null)request.header("Cookie",cookie);if(csrf!=null)request.header("X-XSRF-TOKEN",csrf);
        if("GET".equals(method))request.GET();else request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));return HTTP.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    static HttpResponse<String> business(IdentityApi.Session owner,String path,String method,String body) throws Exception {
        if("GET".equals(method))return request(path,method,null,"QF_SESSION="+owner.token(),null);
        var csrf=request("/api/public/csrf","GET",null,null,null);assertEquals(200,csrf.statusCode());String token=JSON.readTree(csrf.body()).path("token").asString();assertFalse(token.isBlank());return request(path,method,body,"QF_SESSION="+owner.token()+"; XSRF-TOKEN="+token,token);
    }
    static IdentityApi.Session owner(){var guest=identity.create();var signed=identity.signInWithGoogle(guest.token(),guest.creatorId(),unique(),null,null);OWNERS.add(signed.creatorId());long welcome=ledger.balance(signed.creatorId());if(welcome>0)ledger.debit(signed.creatorId(),welcome,"fixture-welcome-used:"+unique());return signed;}
    static Paid pending(IdentityApi.Session owner) throws Exception {
        var response=business(owner,"/api/app/stripe/orders","POST","{\"packageId\":\"starter-USD\",\"amount\":0.01,\"credits\":999999,\"currency\":\"GBP\"}");assertEquals(200,response.statusCode(),response.body());String order=JSON.readTree(response.body()).path("orderId").asString();assertFalse(order.isBlank());
        var stored=db.queryForMap("SELECT * FROM payment_order WHERE id=?",order);assertEquals(new BigDecimal("9.90"),stored.get("amount"));assertEquals("USD",stored.get("currency"));assertEquals(100,((Number)stored.get("credits")).longValue());assertEquals("PENDING",stored.get("status"));assertEquals("STRIPE_SANDBOX",stored.get("provider"));String id=(String)stored.get("provider_link_id");return new Paid(owner,order,id,SESSIONS.get(id).path("payment_intent").asString());
    }
    static Paid paid() throws Exception{return paid(owner());}
    static Paid paid(IdentityApi.Session owner) throws Exception {var paid=pending(owner);SESSIONS.put(paid.session(),session(paid.session(),paid.order(),true));accepted("checkout.session.completed",paid.session(),"evt_"+unique());drain();assertEquals("PAID",db.queryForObject("SELECT status FROM payment_order WHERE id=?",String.class,paid.order()));return paid;}
    static HttpResponse<String> stripeEvent(String kind,String resource,String event) throws Exception {
        String body=JSON.writeValueAsString(Map.of("id",event,"object","event","api_version","2025-03-31.basil","type",kind,"livemode",false,"data",Map.of("object",Map.of("id",resource))));
        String signature=StripeCheckoutGatewayTest.signature(body,SECRET,Instant.now().getEpochSecond());return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/public/stripe/webhook")).timeout(Duration.ofSeconds(25)).header("Content-Type","application/json").header("Stripe-Signature",signature).POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    static void accepted(String kind,String resource,String event) throws Exception {var response=stripeEvent(kind,resource,event);assertEquals(200,response.statusCode(),response.body());assertTrue(JSON.readTree(response.body()).path("received").asBoolean());}
    static void drain(){for(int i=0;i<150;i++){boolean a=stripe.processNextEvent(),b=paypal.processNextEvent();if(!a&&!b)return;}fail("Unbounded due payment backlog");}
    static String refund(Paid p,long amount) throws Exception {String id="re_"+unique();REFUNDS.put(id,JSON.valueToTree(Map.of("id",id,"object","refund","status","succeeded","livemode",false,"payment_intent",p.intent(),"currency","usd","amount",amount)));accepted("refund.updated",id,"evt_"+unique());drain();return id;}
    static JsonNode movement(String id,String source,String type,long amount,String status){var fact=new LinkedHashMap<String,Object>();fact.put("id",id);fact.put("object","balance_transaction");fact.put("source",source);fact.put("type",type);fact.put("amount",amount);fact.put("fee",0);fact.put("net",amount);fact.put("currency","usd");fact.put("exchange_rate",null);fact.put("status",status);return JSON.valueToTree(fact);}
    static void dispute(Paid p,String id,String status,List<JsonNode> movements) throws Exception {DISPUTES.put(id,JSON.valueToTree(Map.of("id",id,"object","dispute","livemode",false,"payment_intent",p.intent(),"currency","usd","amount",990,"status",status,"balance_transactions",movements)));accepted("charge.dispute.updated",id,"evt_"+unique());drain();}
    static long held(Paid p){return number("SELECT held FROM p0_credit_batch WHERE reference=?","payment:"+p.order());}
    static String work(String owner){String work=UUID.randomUUID().toString();db.update("INSERT INTO music_work(id,owner_id,recipient,occasion,story,message,style,title,version,created_at,updated_at) VALUES(?,?,'synthetic','OTHER','synthetic','synthetic','POP','synthetic',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",work,owner);return work;}
    static void due(String event){db.update("UPDATE payment_webhook_inbox SET next_attempt_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE provider='STRIPE_SANDBOX' AND merchant_scope=? AND env_scope='sandbox' AND event_id=?",ACCOUNT,event);}
    static void returnedRefund(Paid p,String refund,String charge,String debit,String credit,String status){
        REFUNDS.put(refund,JSON.valueToTree(Map.of("id",refund,"object","refund","status","failed","payment_intent",p.intent(),"charge",charge,"amount",198,"currency","usd","livemode",false,"balance_transaction",debit,"failure_balance_transaction",credit)));
        var original=new LinkedHashMap<String,Object>();original.put("id",charge);original.put("object","charge");original.put("payment_intent",p.intent());original.put("livemode",false);original.put("status","succeeded");original.put("paid",true);original.put("captured",true);original.put("amount",990);original.put("amount_captured",990);original.put("currency","usd");CHARGES.put(charge,JSON.valueToTree(original));
        BALANCES.put(debit,movement(debit,refund,"refund",-198,"available"));BALANCES.put(credit,movement(credit,refund,"refund_failure",198,status));
    }

    @Test @Order(1) void actualCore40AndTheFullApplicationUseOnlyTheProvenIsolatedDatabase() throws Exception {
        assertEquals(database,db.queryForObject("SELECT DATABASE()",String.class));assertEquals(40,number("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL"));assertEquals(3,number("SELECT protocol FROM p0_credit_writer_policy WHERE id=1"));assertEquals(75,ledger.balance(legacyOwner));assertEquals(25,number("SELECT amount FROM p0_legacy_debit_mapping WHERE reference='synthetic-legacy-debit'"));
        assertInstanceOf(JdbcFencedCreditsLedger.class,ledger);assertNotNull(application.getBean(PaymentInbox.class));assertNotNull(application.getBean(JdbcUsageBudget.class));assertTrue(application.getBeansOfType(PaymentInboxRunner.class).isEmpty());assertTrue(CreditSchemaReadiness.ready(db));
        var health=request("/actuator/health","GET",null,null,null);assertEquals(200,health.statusCode());assertEquals("UP",JSON.readTree(health.body()).path("status").asString());
    }
    @Test @Order(2) void realHttpCreationAndSignedConcurrentDuplicateEventsGrantOnlyOneHundredCredits() throws Exception {
        var p=pending(owner());assertEquals(0,ledger.balance(p.owner().creatorId()));SESSIONS.put(p.session(),session(p.session(),p.order(),true));String same="evt_"+unique();
        var pool=Executors.newFixedThreadPool(8);var httpStart=new CountDownLatch(1);
        try{var calls=new ArrayList<Future<HttpResponse<String>>>();for(int i=0;i<16;i++){String event=i<8?same:"evt_"+unique();calls.add(pool.submit(()->{assertTrue(httpStart.await(10,TimeUnit.SECONDS));return stripeEvent("checkout.session.completed",p.session(),event);}));}httpStart.countDown();for(var future:calls){var response=future.get(30,TimeUnit.SECONDS);assertEquals(200,response.statusCode(),response.body());assertTrue(JSON.readTree(response.body()).path("received").asBoolean());}
            assertEquals(0,ledger.balance(p.owner().creatorId()));var workers=new ArrayList<Future<?>>();var workerStart=new CyclicBarrier(8);
            for(int i=0;i<8;i++)workers.add(pool.submit(()->{workerStart.await(10,TimeUnit.SECONDS);drain();return null;}));for(var worker:workers)worker.get(40,TimeUnit.SECONDS);
        }finally{httpStart.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
        assertEquals(100,ledger.balance(p.owner().creatorId()));assertEquals(1,number("SELECT COUNT(*) FROM credit_ledger WHERE entry_type='CREDIT' AND reference_id=?","payment:"+p.order()));assertEquals(9,number("SELECT COUNT(*) FROM payment_webhook_inbox WHERE provider='STRIPE_SANDBOX' AND payload LIKE ?","%"+p.session()+"%"));
        assertEquals(9,number("SELECT COUNT(*) FROM payment_webhook_inbox WHERE provider='STRIPE_SANDBOX' AND merchant_scope=? AND env_scope='sandbox' AND status='DONE' AND payload LIKE ?",ACCOUNT,"%"+p.session()+"%"));
        assertEquals(0,number("SELECT COUNT(*) FROM payment_inbox_attempt a JOIN payment_webhook_inbox i ON a.provider=i.provider AND a.merchant_scope=i.merchant_scope AND a.env_scope=i.env_scope AND a.event_id=i.event_id WHERE i.provider='STRIPE_SANDBOX' AND i.merchant_scope=? AND i.env_scope='sandbox' AND i.payload LIKE ? AND a.result IN ('FAILED','MANUAL')",ACCOUNT,"%"+p.session()+"%"));
        assertEquals("PAID",JSON.readTree(business(p.owner(),"/api/app/stripe/orders/"+p.order(),"GET",null).body()).path("status").asString());
    }
    @Test @Order(3) void cumulativePartialAndFullRefundsUseSnapshotsAndDifferentEventIdsNeverRepeatAdjustment() throws Exception {
        var p=paid();String one=refund(p,198);assertEquals(80,ledger.balance(p.owner().creatorId()));refund(p,99);assertEquals(70,ledger.balance(p.owner().creatorId()));accepted("refund.updated",one,"evt_"+unique());drain();assertEquals(70,ledger.balance(p.owner().creatorId()));refund(p,693);assertEquals(0,ledger.balance(p.owner().creatorId()));assertEquals(990,number("SELECT SUM(amount_minor) FROM p0_credit_refund WHERE order_id=?",p.order()));assertEquals(100,number("SELECT adjusted FROM p0_credit_batch WHERE reference=?","payment:"+p.order()));
    }
    @Test @Order(4) void orderScopedFreezeSkipsOnlyTheDisputedBatchAndActualUsageCanSpendTheOtherOrder() throws Exception {
        var owner=owner();var a=paid(owner);ledger.debit(owner.creatorId(),80,"synthetic-consumed-a:"+unique());var b=paid(owner);String id="dp_"+unique();dispute(a,id,"under_review",List.of());assertEquals(20,held(a));assertEquals(0,held(b));assertEquals(120,ledger.balance(owner.creatorId()));
        String work=work(owner.creatorId()),key=UUID.randomUUID().toString();usage.reserve(owner.creatorId(),work,key,UsageBudget.Kind.MUSIC);assertEquals(105,ledger.balance(owner.creatorId()));assertEquals(15,number("SELECT consumed FROM p0_credit_batch WHERE reference=?","payment:"+b.order()));assertEquals(20,held(a));
        ledger.debit(owner.creatorId(),85,"synthetic-consume-b:"+unique());assertEquals(20,ledger.balance(owner.creatorId()));assertThrows(DomainException.class,()->ledger.debit(owner.creatorId(),1,"blocked-frozen:"+unique()));dispute(a,id,"won",List.of());assertEquals(0,held(a));ledger.debit(owner.creatorId(),20,"unfrozen-a:"+unique());assertEquals(0,ledger.balance(owner.creatorId()));
    }
    @Test @Order(5) void verifiedLossAllowsDebtButUnifiedUsageRejectsItUntilANewRechargeRestoresBalance() throws Exception {
        var p=paid();ledger.debit(p.owner().creatorId(),80,"consumed-before-loss:"+unique());String id="dp_"+unique();dispute(p,id,"under_review",List.of());assertEquals(20,held(p));var withdrawal=movement("txn_"+unique(),id,"adjustment",-990,"available");dispute(p,id,"lost",List.of(withdrawal));assertEquals(-80,ledger.balance(p.owner().creatorId()));assertEquals(0,held(p));
        String work=work(p.owner().creatorId()),key=UUID.randomUUID().toString();assertThrows(DomainException.class,()->usage.reserve(p.owner().creatorId(),work,key,UsageBudget.Kind.MUSIC));assertEquals(0,number("SELECT COUNT(*) FROM model_usage_reservation WHERE work_id=? AND request_key=?",work,key));paid(p.owner());assertEquals(20,ledger.balance(p.owner().creatorId()));
    }
    @Test @Order(6) void completeDisputeReturnedFundsPreserveEarlierSuccessfulPartialRefunds() throws Exception {
        var p=paid();refund(p,198);assertEquals(80,ledger.balance(p.owner().creatorId()));String id="dp_"+unique();var loss=movement("txn_"+unique(),id,"adjustment",-990,"available");dispute(p,id,"lost",List.of(loss));assertEquals(0,ledger.balance(p.owner().creatorId()));var returned=movement("txn_"+unique(),id,"adjustment",990,"available");dispute(p,id,"won",List.of(loss,returned));assertEquals(80,ledger.balance(p.owner().creatorId()));dispute(p,id,"won",List.of(loss,returned));assertEquals(80,ledger.balance(p.owner().creatorId()));assertEquals(1,number("SELECT COUNT(*) FROM p0_credit_return WHERE order_id=?",p.order()));assertEquals(20,number("SELECT adjusted FROM p0_credit_batch WHERE reference=?","payment:"+p.order()));
    }
    @Test @Order(7) void actualPayPalCreateCaptureAndRepeatedWebhookKeepTheOriginalProviderAndSingleCredit() throws Exception {
        var owner=owner();var created=business(owner,"/api/app/paypal/orders","POST","{\"packageId\":\"starter-USD\"}");assertEquals(200,created.statusCode(),created.body());String order=JSON.readTree(created.body()).path("orderId").asString();var capture=business(owner,"/api/app/paypal/orders/"+order+"/capture","POST","{}");assertEquals(200,capture.statusCode(),capture.body());assertEquals("PAID",JSON.readTree(capture.body()).path("status").asString());assertEquals(100,ledger.balance(owner.creatorId()));
        String remote=db.queryForObject("SELECT provider_link_id FROM payment_order WHERE id=?",String.class,order),captureId=PAYPAL_ORDERS.get(remote).captureId();String body=JSON.writeValueAsString(Map.of("id","WH-"+unique(),"event_type","PAYMENT.CAPTURE.COMPLETED","resource",Map.of("id",captureId,"supplementary_data",Map.of("related_ids",Map.of("order_id",remote)))));
        for(int i=0;i<2;i++)assertEquals(200,request("/api/public/paypal/webhook","POST",body,null,null).statusCode());drain();assertEquals(100,ledger.balance(owner.creatorId()));assertEquals(1,number("SELECT COUNT(*) FROM credit_ledger WHERE entry_type='CREDIT' AND reference_id=?","payment:"+order));assertEquals("PAYPAL_SANDBOX",db.queryForObject("SELECT provider FROM payment_order WHERE id=?",String.class,order));assertEquals(1,number("SELECT COUNT(*) FROM p0_credit_payment_binding WHERE order_id=? AND provider='PAYPAL' AND merchant=? AND environment='sandbox'",order,PAYPAL_MERCHANT));
    }
    @Test @Order(8) void refundReturnMutationAndInboxCompletionRollBackTogetherThenRetryOnce() throws Exception {
        var p=paid();String refund=refund(p,198),debit="txn_"+unique(),credit="txn_"+unique(),event="evt_"+unique();returnedRefund(p,refund,"ch_"+unique(),debit,credit,"available");accepted("refund.failed",refund,event);
        db.execute("CREATE TRIGGER application_synthetic_return_failure BEFORE INSERT ON p0_credit_refund_return FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='SYNTHETIC_RETURN_ROLLBACK'");
        try{assertTrue(stripe.processNextEvent());assertEquals(80,ledger.balance(p.owner().creatorId()));assertEquals(0,number("SELECT COUNT(*) FROM p0_credit_refund_return WHERE order_id=?",p.order()));assertEquals(0,number("SELECT COUNT(*) FROM p0_credit_native_identity WHERE kind='REFUND_RETURN' AND resource_id=?",refund));assertEquals("PENDING",db.queryForObject("SELECT status FROM payment_webhook_inbox WHERE event_id=?",String.class,event));}finally{db.execute("DROP TRIGGER application_synthetic_return_failure");}
        due(event);drain();assertEquals(100,ledger.balance(p.owner().creatorId()));accepted("refund.failed",refund,"evt_"+unique());drain();assertEquals(100,ledger.balance(p.owner().creatorId()));assertEquals(1,number("SELECT COUNT(*) FROM p0_credit_refund_return WHERE order_id=?",p.order()));assertEquals(1,number("SELECT COUNT(*) FROM credit_ledger WHERE owner_id=? AND entry_type='REFUND_RETURN'",p.owner().creatorId()));
    }
    @Test @Order(9) void httpOwnerIsolationAndSdkSignatureFailureCannotMutateFunds() throws Exception {
        var p=paid();var other=owner();var denied=business(other,"/api/app/stripe/orders/"+p.order(),"GET",null);assertEquals(404,denied.statusCode());String body="{\"id\":\"evt_offline12345678\",\"type\":\"refund.updated\",\"livemode\":false,\"data\":{\"object\":{\"id\":\"re_offline12345678\"}}}";
        var invalid=HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/public/stripe/webhook")).header("Content-Type","application/json").header("Stripe-Signature",StripeCheckoutGatewayTest.signature(body,"whsec_wrong_offline_fixture",Instant.now().getEpochSecond())).POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());assertEquals(401,invalid.statusCode());assertEquals(0,number("SELECT COUNT(*) FROM payment_webhook_inbox WHERE event_id='evt_offline12345678'"));assertEquals(100,ledger.balance(p.owner().creatorId()));assertEquals(0,ledger.balance(other.creatorId()));
    }
    @Test @Order(10) void fullContextRestartRetainsPendingCompensationAndAppliesAvailablePrincipalOnlyOnce() throws Exception {
        var p=paid();String refund=refund(p,198),debit="txn_"+unique(),credit="txn_"+unique(),event="evt_"+unique();returnedRefund(p,refund,"ch_"+unique(),debit,credit,"pending");accepted("refund.failed",refund,event);drain();assertEquals(80,ledger.balance(p.owner().creatorId()));assertEquals(0,number("SELECT attempts FROM payment_webhook_inbox WHERE event_id=?",event));assertEquals("PROVIDER_PENDING",db.queryForObject("SELECT last_error FROM payment_webhook_inbox WHERE event_id=?",String.class,event));assertEquals(1,number("SELECT COUNT(*) FROM payment_inbox_attempt WHERE event_id=? AND result='WAITING'",event));
        var before=application;application.close();start();assertNotSame(before,application);assertEquals(80,ledger.balance(p.owner().creatorId()));assertEquals("PAID",db.queryForObject("SELECT status FROM payment_order WHERE id=?",String.class,p.order()));assertEquals(40,number("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL"));
        BALANCES.put(credit,movement(credit,refund,"refund_failure",198,"available"));due(event);drain();assertEquals(100,ledger.balance(p.owner().creatorId()));accepted("refund.failed",refund,"evt_"+unique());drain();assertEquals(100,ledger.balance(p.owner().creatorId()));assertEquals(1,number("SELECT COUNT(*) FROM p0_credit_refund_return WHERE order_id=?",p.order()));assertEquals(1,number("SELECT COUNT(*) FROM credit_ledger WHERE owner_id=? AND entry_type='REFUND_RETURN'",p.owner().creatorId()));
    }
    static AvatarGenerationService avatar(AvatarImageProvider provider){return new AvatarGenerationService(db,new TransactionTemplate(manager),application.getBean(AvatarService.class),provider,ledger,application.getBean(CreditsPricing.class),10);}
    @Test @Order(11) void currentMainAvatarChargesFiveOnceKeepsUnknownChargedAndRestoresDefinitiveFailureToItsOriginalBatch() throws Exception {
        var successful=paid();var provider=mock(AvatarImageProvider.class);when(provider.configured()).thenReturn(true);when(provider.submit("synthetic avatar")).thenReturn("remote-offline");var service=avatar(provider);String key=UUID.randomUUID().toString();
        var first=service.start(successful.owner().creatorId(),key,"synthetic avatar");assertEquals("RUNNING",first.status());assertEquals(first.id(),service.start(successful.owner().creatorId(),key,"synthetic avatar").id());assertEquals(95,ledger.balance(successful.owner().creatorId()));verify(provider,times(1)).submit("synthetic avatar");
        assertEquals(-5,number("SELECT SUM(amount) FROM credit_ledger WHERE entry_type='DEBIT' AND reference_id=?","avatar:"+first.id()));assertEquals(5,number("SELECT consumed FROM p0_credit_batch WHERE reference=?","payment:"+successful.order()));
        var unknown=paid();var unknownProvider=mock(AvatarImageProvider.class);when(unknownProvider.configured()).thenReturn(true);when(unknownProvider.submit("unknown result")).thenThrow(new IllegalStateException("synthetic provider acceptance unknown"));var unknownService=avatar(unknownProvider);String unknownKey=UUID.randomUUID().toString();var unknownTask=unknownService.start(unknown.owner().creatorId(),unknownKey,"unknown result");
        assertEquals("UNKNOWN",unknownTask.status());assertEquals(unknownTask.id(),unknownService.start(unknown.owner().creatorId(),unknownKey,"unknown result").id());assertEquals(95,ledger.balance(unknown.owner().creatorId()));verify(unknownProvider,times(1)).submit("unknown result");assertEquals(0,number("SELECT COUNT(*) FROM credit_ledger WHERE entry_type='REFUND' AND reference_id=?","avatar:"+unknownTask.id()));
        var failed=paid();var failedProvider=mock(AvatarImageProvider.class);when(failedProvider.configured()).thenReturn(true);when(failedProvider.submit("rejected result")).thenThrow(new AvatarImageProvider.Rejected());var failedService=avatar(failedProvider);String failedKey=UUID.randomUUID().toString();var failedTask=failedService.start(failed.owner().creatorId(),failedKey,"rejected result");
        assertEquals("FAILED",failedTask.status());assertEquals(failedTask.id(),failedService.start(failed.owner().creatorId(),failedKey,"rejected result").id());assertEquals(100,ledger.balance(failed.owner().creatorId()));verify(failedProvider,times(1)).submit("rejected result");assertEquals(1,number("SELECT COUNT(*) FROM credit_ledger WHERE entry_type='REFUND' AND reference_id=?","avatar:"+failedTask.id()));assertEquals(0,number("SELECT consumed FROM p0_credit_batch WHERE reference=?","payment:"+failed.order()));assertEquals(1,number("SELECT COUNT(*) FROM p0_credit_allocation WHERE debit_reference=? AND batch_reference=? AND amount=5 AND restored=1","avatar:"+failedTask.id(),"payment:"+failed.order()));
        for(var mock:List.of(provider,unknownProvider,failedProvider)){verify(mock,never()).query(anyString());verify(mock,never()).download(anyString());}
    }
    @Test @Order(12) void paypalUnresolvedReversalHoldsOnlyItsOrderWithoutPermanentDeductionOrBlockingTheOtherBatch() throws Exception {
        var owner=owner();var created=business(owner,"/api/app/paypal/orders","POST","{\"packageId\":\"starter-USD\"}");assertEquals(200,created.statusCode(),created.body());String a=JSON.readTree(created.body()).path("orderId").asString();assertEquals(200,business(owner,"/api/app/paypal/orders/"+a+"/capture","POST","{}").statusCode());assertEquals(100,ledger.balance(owner.creatorId()));ledger.debit(owner.creatorId(),80,"paypal-used:"+unique());var b=paid(owner);
        String remote=db.queryForObject("SELECT provider_link_id FROM payment_order WHERE id=?",String.class,a),capture=PAYPAL_ORDERS.get(remote).captureId(),event="WH-"+unique();var gateway=application.getBean(PayPalCheckoutGateway.class);
        doReturn(JSON.valueToTree(Map.of("id",capture,"status","REVERSED","amount",Map.of("currency_code","USD","value","9.90"),"supplementary_data",Map.of("related_ids",Map.of("order_id",remote))))).when(gateway).retrieveCapture(capture);
        String body=JSON.writeValueAsString(Map.of("id",event,"event_type","PAYMENT.CAPTURE.REVERSED","resource",Map.of("id",capture,"supplementary_data",Map.of("related_ids",Map.of("order_id",remote)))));assertEquals(200,request("/api/public/paypal/webhook","POST",body,null,null).statusCode());drain();
        assertEquals(120,ledger.balance(owner.creatorId()));assertEquals(20,number("SELECT held FROM p0_credit_batch WHERE reference=?","payment:"+a));assertEquals("REVIEW",db.queryForObject("SELECT status FROM p0_credit_batch WHERE reference=?",String.class,"payment:"+a));assertEquals(0,held(b));assertEquals(0,number("SELECT adjusted FROM p0_credit_batch WHERE reference=?","payment:"+a));assertEquals(0,number("SELECT payment_risk_frozen FROM creator WHERE id=?",owner.creatorId()));
        assertEquals("PENDING",db.queryForObject("SELECT status FROM payment_webhook_inbox WHERE event_id=?",String.class,event));assertEquals(0,number("SELECT attempts FROM payment_webhook_inbox WHERE event_id=?",event));assertEquals("WAITING",db.queryForObject("SELECT result FROM payment_inbox_attempt WHERE event_id=?",String.class,event));ledger.debit(owner.creatorId(),100,"paypal-other-batch:"+unique());assertEquals(20,ledger.balance(owner.creatorId()));assertThrows(DomainException.class,()->ledger.debit(owner.creatorId(),1,"paypal-held:"+unique()));
    }
}
