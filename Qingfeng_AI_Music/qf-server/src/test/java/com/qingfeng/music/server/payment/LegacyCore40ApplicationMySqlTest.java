package com.qingfeng.music.server.payment;

import com.qingfeng.music.infrastructure.persistence.credits.LocalFinancialCohort;
import com.qingfeng.music.credits.CreditsLedger;
import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.infrastructure.persistence.credits.JdbcCreditsLedger;
import com.qingfeng.music.infrastructure.persistence.credits.JdbcFencedCreditsLedger;
import com.qingfeng.music.server.MusicApplication;
import com.qingfeng.music.server.config.CreditSchemaReadiness;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
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
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Historical selector retained: real default-target core44 Boot/HTTP/MySQL compatibility.
 * Provider verification and funds are synthetic; this never authorizes financial V45 or real payments. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LegacyCore40ApplicationMySqlTest {
    static final String PREFIX="P0_CORE40_APPLICATION_MYSQL_",MERCHANT="TESTMERCHANT";
    static final ObjectMapper JSON=new ObjectMapper();
    static final Map<String,PayPalCheckoutGateway.Snapshot> REMOTE=new ConcurrentHashMap<>();
    static final Set<String> OWNERS=ConcurrentHashMap.newKeySet();
    static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    static ConfigurableApplicationContext application;static JdbcTemplate db;static CreditsLedger ledger;static IdentityApi identity;static PayPalPaymentService paypal;static PaymentInbox inbox;
    static String database,url,user,password;static int port;static Map<String,Object> settings;
    static String unique(){return UUID.randomUUID().toString().replace("-","");}
    static String required(String suffix){String value=System.getenv(PREFIX+suffix);if(value==null||value.isBlank())throw new IllegalStateException("Explicit isolated core40 resource required: "+PREFIX+suffix);return value;}
    static long number(String sql,Object...args){return Objects.requireNonNull(db.queryForObject(sql,Long.class,args));}

    @BeforeAll static void proveEmptySingleSchemaThenBootWithActualUnmodifiedDefaults() throws Exception {
        database=required("DATABASE");url=required("URL");user=required("USER");password=required("PASSWORD");
        if(!database.matches("p0_core40_application_20261112r[0-9]+")||!url.matches("jdbc:mysql://127\\.0\\.0\\.1:23316/"+database+"(?:\\?.*)?")||"root".equalsIgnoreCase(user)||url.toLowerCase(Locale.ROOT).matches(".*(?:password=|user=|socketfactory|dnssrv|loadbalance|address=).*"))throw new IllegalStateException("Only a fresh explicitly isolated core40 schema is permitted");
        var source=new DriverManagerDataSource(url,user,password);db=new JdbcTemplate(source);
        try(var connection=source.getConnection();var statement=connection.createStatement()){
            try(var facts=statement.executeQuery("SELECT VERSION(),@@server_uuid,@@datadir,@@port,@@bind_address,DATABASE(),CURRENT_ROLE(),@@transaction_isolation")){
                assertTrue(facts.next());assertEquals("8.0.46",facts.getString(1));
                LocalFinancialCohort.assertServer(required("UUID"),facts.getString(2),facts.getString(3));
                assertEquals(23316,facts.getInt(4));assertEquals("127.0.0.1",facts.getString(5));assertEquals(database,facts.getString(6));assertEquals("NONE",facts.getString(7));assertEquals("REPEATABLE-READ",facts.getString(8));
            }
            int grants=0;try(var facts=statement.executeQuery("SHOW GRANTS")){while(facts.next()){String grant=facts.getString(1);assertTrue(grant.startsWith("GRANT USAGE ON *.*")||grant.contains("ON `"+database+"`.*"));assertFalse(grant.contains("WITH GRANT OPTION"));grants++;}}assertEquals(2,grants);
            try(var facts=statement.executeQuery("SELECT SCHEMA_NAME FROM information_schema.SCHEMATA")){while(facts.next())assertTrue(Set.of(database,"information_schema","performance_schema").contains(facts.getString(1)));}
        }
        assertEquals(0,number("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()"),"Never clear or reuse another test cohort");
        settings=configuration();assertFalse(settings.containsKey("spring.flyway.target"));assertFalse(settings.containsKey("qingfeng.credits.writer-fence.enabled"));
        // No manual Flyway migration: the real application default must choose core40 itself.
        application=builder(settings).run();db=application.getBean(JdbcTemplate.class);ledger=application.getBean(CreditsLedger.class);identity=application.getBean(IdentityApi.class);paypal=application.getBean(PayPalPaymentService.class);inbox=application.getBean(PaymentInbox.class);port=application.getEnvironment().getRequiredProperty("local.server.port",Integer.class);
    }
    static Map<String,Object> configuration(){
        Map<String,Object> values=new LinkedHashMap<>();values.put("spring.datasource.url",url);values.put("spring.datasource.username",user);values.put("spring.datasource.password",password);values.put("spring.datasource.driver-class-name","com.mysql.cj.jdbc.Driver");values.put("spring.datasource.hikari.maximum-pool-size",16);values.put("spring.datasource.hikari.connection-init-sql","SET SESSION innodb_lock_wait_timeout=5");values.put("spring.flyway.clean-disabled",true);values.put("spring.sql.init.mode","never");values.put("server.address","127.0.0.1");values.put("server.port",0);
        values.put("qingfeng.paypal.enabled",true);values.put("qingfeng.paypal.environment","sandbox");values.put("qingfeng.paypal.client-id","offline-core40-fixture");values.put("qingfeng.paypal.client-secret","offline-core40-fixture");values.put("qingfeng.paypal.merchant-id",MERCHANT);values.put("qingfeng.paypal.webhook-id","offline-core40-fixture");
        // Even an otherwise configured Stripe channel must remain closed on the unapproved writer.
        values.put("qingfeng.stripe.enabled",true);values.put("qingfeng.stripe.accepting-new-orders",true);values.put("qingfeng.stripe.environment","sandbox");values.put("qingfeng.stripe.account-id","acct_core40_offline123");values.put("qingfeng.stripe.api-key","sk_test_core40_offline_fixture");values.put("qingfeng.stripe.webhook-secret","whsec_core40_offline_fixture");
        for(String key:List.of("qingfeng.payment.inbox.scheduler-enabled","qingfeng.events.dispatcher-enabled","qingfeng.events.rabbit.enabled","qingfeng.redis.enabled","qingfeng.generation.enabled","qingfeng.generation.scheduler-enabled","qingfeng.cover.ai.enabled","qingfeng.cover.ai.scheduler-enabled","qingfeng.analytics.retention.scheduler-enabled","qingfeng.quick.enabled","qingfeng.invite-gate.enabled","qingfeng.payments.enabled","qingfeng.agent.chat.enabled","qingfeng.auth.google.enabled","qingfeng.auth.logto.enabled","qingfeng.reference.analysis.enabled","qingfeng.music-settings.simplify.enabled","qingfeng.lyrics.timing.enabled","qingfeng.lyrics.enabled","qingfeng.inspiration.rewrite.enabled","qingfeng.avatar.ai.enabled","qingfeng.avatar.ai.scheduler-enabled"))values.put(key,false);
        values.put("qingfeng.cover.directory","./target/legacy-core40-covers");values.put("qingfeng.media.directory","./target/legacy-core40-audio");return values;
    }
    static SpringApplicationBuilder builder(Map<String,Object> values){
        var env=new StandardEnvironment();env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);env.getPropertySources().addFirst(new MapPropertySource("isolated-core40-acceptance-only",values));
        return new SpringApplicationBuilder(MusicApplication.class,OfflineProviders.class).environment(env).profiles("mysql").web(WebApplicationType.SERVLET).logStartupInfo(false).initializers(context->context.getBeanFactory().registerSingleton("legacyCoreOnlyTestConfigurationFilter",new AcceptanceOnlyTestFilter()));
    }
    static final class AcceptanceOnlyTestFilter extends org.springframework.boot.context.TypeExcludeFilter {
        @Override public boolean match(org.springframework.core.type.classreading.MetadataReader reader,org.springframework.core.type.classreading.MetadataReaderFactory factory)throws java.io.IOException{return reader.getResource().getURL().getPath().replace('\\','/').contains("/test-classes/");}
        @Override public boolean equals(Object other){return other instanceof AcceptanceOnlyTestFilter;}@Override public int hashCode(){return AcceptanceOnlyTestFilter.class.hashCode();}
    }
    @TestConfiguration(proxyBeanMethods=false) static class OfflineProviders {
        @Bean @Primary StripeCheckoutGateway closedStripe(){
            var gateway=mock(StripeCheckoutGateway.class,invocation->{if(invocation.getMethod().getDeclaringClass()==Object.class)return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);throw new AssertionError("Unexpected Stripe provider invocation: "+invocation.getMethod().getName());});
            doReturn(true).when(gateway).configured();doReturn("STRIPE_SANDBOX").when(gateway).provider();doReturn("sandbox").when(gateway).environment();doReturn("acct_core40_offline123").when(gateway).merchantId();return gateway;
        }
        @Bean PayPalCheckoutGateway syntheticPayPal(){
            var gateway=mock(PayPalCheckoutGateway.class,invocation->{if(invocation.getMethod().getDeclaringClass()==Object.class)return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);throw new AssertionError("Unexpected PayPal provider invocation: "+invocation.getMethod().getName());});
            doAnswer(call->{String order=call.getArgument(0),remote="P"+unique().substring(0,20);REMOTE.put(remote,new PayPalCheckoutGateway.Snapshot(remote,order,"APPROVED",MERCHANT,"","",new BigDecimal("9.90"),"USD"));return new PayPalCheckoutGateway.Created(remote,"https://www.sandbox.paypal.com/checkoutnow?token="+remote);}).when(gateway).create(anyString(),anyString(),any(),anyString(),anyString(),anyString());
            doAnswer(call->{var fact=REMOTE.get(call.getArgument(0));if(fact==null)throw new AssertionError("Unknown synthetic PayPal resource");return fact;}).when(gateway).retrieve(anyString());
            doAnswer(call->{String remote=call.getArgument(0);var prior=REMOTE.get(remote);if(prior==null)throw new AssertionError("Unknown synthetic capture");var fact=completed(prior);REMOTE.put(remote,fact);return fact;}).when(gateway).capture(anyString(),anyString());
            doAnswer(call->{PayPalCheckoutGateway.WebhookHeaders headers=call.getArgument(0);return headers!=null&&"offline-verified-signature".equals(headers.transmissionSig());}).when(gateway).verifyWebhook(any(),any());return gateway;
        }
        @Bean @Primary PayPalPaymentService offlinePayPalService(JdbcTemplate db,PlatformTransactionManager manager,CreditsLedger credits,PaymentCatalog catalog,Environment env,PayPalCheckoutGateway syntheticPayPal){return new PayPalPaymentService(db,new TransactionTemplate(manager),credits,catalog,env){@Override PayPalCheckoutGateway gateway(){return syntheticPayPal;}};}
    }
    static PayPalCheckoutGateway.Snapshot completed(PayPalCheckoutGateway.Snapshot prior){return new PayPalCheckoutGateway.Snapshot(prior.orderId(),prior.reference(),"COMPLETED",MERCHANT,"C"+unique().substring(0,20),"COMPLETED",new BigDecimal("9.90"),"USD");}
    @AfterAll static void close(){if(application!=null)application.close();}
    @AfterEach void legacyDatabaseAndEveryStoredBalanceRemainConserved(){
        assertEquals(44,number("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL"));assertEquals(0,number("SELECT COUNT(*) FROM flyway_schema_history WHERE version='45'"));assertEquals(0,number("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME LIKE 'p0\\_%'"));
        for(String owner:OWNERS){long sum=number("SELECT COALESCE(SUM(amount),0) FROM credit_ledger WHERE owner_id=?",owner);assertEquals(sum,number("SELECT COALESCE(SUM(balance),0) FROM credit_account WHERE owner_id=?",owner));assertEquals(sum,ledger.balance(owner));}
    }
    static IdentityApi.Session owner(){var guest=identity.create();var user=identity.signInWithGoogle(guest.token(),guest.creatorId(),unique(),null,null);OWNERS.add(user.creatorId());long welcome=ledger.balance(user.creatorId());assertEquals(19,welcome);ledger.debit(user.creatorId(),welcome,"fixture-welcome-used:"+unique());return user;}
    static HttpResponse<String> request(String path,String method,String body,String cookie,String csrf)throws Exception{var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(25));if(cookie!=null)req.header("Cookie",cookie);if(csrf!=null)req.header("X-XSRF-TOKEN",csrf);if(method.equals("GET"))req.GET();else req.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));return HTTP.send(req.build(),HttpResponse.BodyHandlers.ofString());}
    static HttpResponse<String> business(IdentityApi.Session owner,String path,String method,String body)throws Exception{if(method.equals("GET"))return request(path,method,null,"QF_SESSION="+owner.token(),null);var csrf=request("/api/public/csrf","GET",null,null,null);assertEquals(200,csrf.statusCode());String token=JSON.readTree(csrf.body()).path("token").asString();return request(path,method,body,"QF_SESSION="+owner.token()+"; XSRF-TOKEN="+token,token);}
    static String create(IdentityApi.Session owner)throws Exception{var response=business(owner,"/api/app/paypal/orders","POST","{\"packageId\":\"starter-USD\"}");assertEquals(200,response.statusCode(),response.body());return JSON.readTree(response.body()).path("orderId").asString();}
    @Test @Order(0) void discover44StartsWithTheLegacyWriterAndRealPublicReadWithoutInstallingFinance45() throws Exception {
        assertEquals("44",application.getEnvironment().getProperty("spring.flyway.target"));
        assertFalse(CreditSchemaReadiness.installed(db));CreditSchemaReadiness.requireCore44Ready(db);
        assertEquals("V44__discover_song_publication.sql",db.queryForObject("SELECT script FROM flyway_schema_history WHERE version='44'",String.class));
        assertEquals(-1983576296,number("SELECT checksum FROM flyway_schema_history WHERE version='44'"));
        assertEquals(0,number("SELECT COUNT(*) FROM flyway_schema_history WHERE version='45'"));
        var response=request("/api/public/discover","GET",null,null,null);assertEquals(200,response.statusCode(),response.body());
        var page=JSON.readTree(response.body());assertTrue(page.path("songs").isArray());assertEquals(0,page.path("songs").size());
        assertTrue(page.path("artists").isArray());assertEquals(0,page.path("artists").size());assertFalse(page.path("hasMore").asBoolean());
        assertInstanceOf(JdbcCreditsLedger.class,ledger);assertFalse(ledger instanceof JdbcFencedCreditsLedger);
    }
    static HttpResponse<String> callback(String body,boolean valid)throws Exception{return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/public/paypal/webhook")).timeout(Duration.ofSeconds(25)).header("Content-Type","application/json").header("PAYPAL-AUTH-ALGO","SHA256withRSA").header("PAYPAL-CERT-URL","https://api.sandbox.paypal.com/offline-cert").header("PAYPAL-TRANSMISSION-ID","offline-transmission").header("PAYPAL-TRANSMISSION-TIME","2026-11-13T00:00:00Z").header("PAYPAL-TRANSMISSION-SIG",valid?"offline-verified-signature":"offline-rejected-signature").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());}

    @Test @Order(1) void defaultCore40StartsWithUnchangedLedgerAndStripeRemainsClosed()throws Exception{
        assertEquals("44",application.getEnvironment().getProperty("spring.flyway.target"));assertFalse(application.getEnvironment().getProperty("qingfeng.credits.writer-fence.enabled",Boolean.class,true));assertInstanceOf(JdbcCreditsLedger.class,ledger);assertFalse(ledger instanceof JdbcFencedCreditsLedger);CreditSchemaReadiness.requireCore44Ready(db);
        assertEquals(-500011050,number("SELECT checksum FROM flyway_schema_history WHERE version='42'"));assertEquals(1,number("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='shared_download_entitlement'"));assertTrue(application.getBeansOfType(PaymentInboxRunner.class).isEmpty());assertEquals(0,number("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='payment_webhook_inbox' AND COLUMN_NAME IN ('merchant_scope','env_scope')"));
        var user=owner();var health=request("/actuator/health","GET",null,null,null);assertEquals(200,health.statusCode());assertEquals("UP",JSON.readTree(health.body()).path("status").asString());var stripeCatalog=business(user,"/api/app/stripe/catalog","GET",null);assertEquals(200,stripeCatalog.statusCode());assertFalse(JSON.readTree(stripeCatalog.body()).path("enabled").asBoolean());
        var denied=business(user,"/api/app/stripe/orders","POST","{\"packageId\":\"starter-USD\"}");assertEquals(503,denied.statusCode());assertEquals(0,number("SELECT COUNT(*) FROM payment_order WHERE provider LIKE 'STRIPE%'"));verify(application.getBean(StripeCheckoutGateway.class),never()).verifyAccount();verify(application.getBean(StripeCheckoutGateway.class),never()).createSession(anyString(),anyString(),any(),anyString());
    }
    @Test @Order(2) void actualLegacyPayPalCreateCaptureAndQueryGrantSnapshotCreditsOnlyOnce()throws Exception{
        var user=owner();String order=create(user);var captured=business(user,"/api/app/paypal/orders/"+order+"/capture","POST","{}");assertEquals(200,captured.statusCode(),captured.body());assertEquals("PAID",JSON.readTree(captured.body()).path("status").asString());assertEquals(100,ledger.balance(user.creatorId()));
        assertEquals(200,business(user,"/api/app/paypal/orders/"+order+"/capture","POST","{}").statusCode());assertEquals(100,ledger.balance(user.creatorId()));assertEquals(1,number("SELECT COUNT(*) FROM credit_ledger WHERE entry_type='CREDIT' AND reference_id=? AND amount=100","payment:"+order));
        var queried=business(user,"/api/app/paypal/orders/"+order,"GET",null);assertEquals(200,queried.statusCode());assertEquals("PAID",JSON.readTree(queried.body()).path("status").asString());assertEquals("PAYPAL_SANDBOX",db.queryForObject("SELECT provider FROM payment_order WHERE id=?",String.class,order));assertEquals(new BigDecimal("9.90"),db.queryForObject("SELECT amount FROM payment_order WHERE id=?",BigDecimal.class,order));assertEquals("USD",db.queryForObject("SELECT currency FROM payment_order WHERE id=?",String.class,order));
    }
    @Test @Order(3) void verifiedLegacyPointersAndConcurrentDuplicateWebhooksDurablySettleOnce()throws Exception{
        var user=owner();String order=create(user),remote=db.queryForObject("SELECT provider_link_id FROM payment_order WHERE id=?",String.class,order);var fact=completed(REMOTE.get(remote));REMOTE.put(remote,fact);String repeated="WH-"+unique();var events=new ArrayList<String>();for(int i=0;i<16;i++)events.add(i<8?repeated:"WH-"+unique());
        var pool=Executors.newFixedThreadPool(8);var start=new CountDownLatch(1);
        try{var calls=new ArrayList<Future<HttpResponse<String>>>();for(String event:events){String body=JSON.writeValueAsString(Map.of("id",event,"event_type","PAYMENT.CAPTURE.COMPLETED","payer",Map.of("email_address","discarded@example.invalid"),"resource",Map.of("id",fact.captureId(),"supplementary_data",Map.of("related_ids",Map.of("order_id",remote)))));calls.add(pool.submit(()->{assertTrue(start.await(15,TimeUnit.SECONDS));return callback(body,true);}));}start.countDown();for(var call:calls)assertEquals(200,call.get(30,TimeUnit.SECONDS).statusCode());
            assertEquals(0,ledger.balance(user.creatorId()));assertEquals(9,number("SELECT COUNT(*) FROM payment_webhook_inbox WHERE provider='PAYPAL_SANDBOX' AND payload LIKE ? AND status='PENDING'","%"+remote+"%"));
            var workers=new ArrayList<Future<?>>();var barrier=new CyclicBarrier(8);for(int i=0;i<8;i++)workers.add(pool.submit(()->{barrier.await(15,TimeUnit.SECONDS);for(int n=0;n<40;n++)if(!paypal.processNextEvent())return null;throw new AssertionError("Unbounded legacy payment backlog");}));for(var worker:workers)worker.get(40,TimeUnit.SECONDS);
        }finally{start.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(15,TimeUnit.SECONDS));}
        assertEquals(100,ledger.balance(user.creatorId()));assertEquals(1,number("SELECT COUNT(*) FROM credit_ledger WHERE entry_type='CREDIT' AND reference_id=?","payment:"+order));assertEquals(9,number("SELECT COUNT(*) FROM payment_webhook_inbox WHERE provider='PAYPAL_SANDBOX' AND payload LIKE ? AND status='DONE' AND attempts=1","%"+remote+"%"));assertFalse(paypal.processNextEvent());
        for(String pointer:db.query("SELECT payload FROM payment_webhook_inbox WHERE provider='PAYPAL_SANDBOX' AND payload LIKE ?",(rs,n)->rs.getString(1),"%"+remote+"%")){var json=JSON.readTree(pointer);assertEquals(2,json.size());assertEquals(remote,json.path("orderId").asString());assertEquals(fact.captureId(),json.path("resourceId").asString());assertFalse(pointer.contains("discarded@example.invalid"));assertFalse(pointer.contains("signature"));}
        String body=JSON.writeValueAsString(Map.of("id",repeated,"event_type","PAYMENT.CAPTURE.COMPLETED","resource",Map.of("id",fact.captureId(),"supplementary_data",Map.of("related_ids",Map.of("order_id",remote)))));assertEquals(200,callback(body,true).statusCode());assertFalse(paypal.processNextEvent());assertEquals(100,ledger.balance(user.creatorId()));
        String invalid=JSON.writeValueAsString(Map.of("id","WH-invalid-"+unique(),"event_type","PAYMENT.CAPTURE.COMPLETED","resource",Map.of("id",fact.captureId())));assertEquals(401,callback(invalid,false).statusCode());assertEquals(9,number("SELECT COUNT(*) FROM payment_webhook_inbox WHERE provider='PAYPAL_SANDBOX' AND payload LIKE ?","%"+remote+"%"));verify(application.getBean(PayPalCheckoutGateway.class),never()).capture(eq(remote),anyString());
    }
    @Test @Order(4) void scopedCallsAndUnapprovedFenceFailClosedWithoutMigratingOrChangingLegacyMoney(){
        long journal=number("SELECT COUNT(*) FROM credit_ledger"),orders=number("SELECT COUNT(*) FROM payment_order"),callbacks=number("SELECT COUNT(*) FROM payment_webhook_inbox");BigDecimal amount=db.queryForObject("SELECT SUM(amount) FROM credit_ledger",BigDecimal.class),balances=db.queryForObject("SELECT SUM(balance) FROM credit_account",BigDecimal.class);
        assertThrows(IllegalStateException.class,()->inbox.acceptVerified("STRIPE_SANDBOX","acct_core40_offline123","sandbox","evt_"+unique(),"refund.updated","{}"));assertThrows(IllegalStateException.class,()->inbox.claim("STRIPE_SANDBOX","acct_core40_offline123","sandbox"));
        var rejected=new LinkedHashMap<>(settings);rejected.put("qingfeng.credits.writer-fence.enabled",true);rejected.put("qingfeng.credits.writer-fence.expected-database",database);rejected.put("qingfeng.credits.writer-fence.expected-port",23316);assertFalse(rejected.containsKey("spring.flyway.target"));
        RuntimeException failure=assertThrows(RuntimeException.class,()->{try(var unexpectedlyStarted=builder(rejected).run()){fail("Unapproved core40 cannot start the protocol-3 writer");}});boolean correct=false;for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(cause.getMessage()!=null&&cause.getMessage().contains("Credit schema prerequisite failed"))correct=true;assertTrue(correct,"Must fail at the schema gate rather than an unrelated provider/configuration error");
        assertTrue(application.isActive());assertEquals(journal,number("SELECT COUNT(*) FROM credit_ledger"));assertEquals(orders,number("SELECT COUNT(*) FROM payment_order"));assertEquals(callbacks,number("SELECT COUNT(*) FROM payment_webhook_inbox"));assertEquals(amount,db.queryForObject("SELECT SUM(amount) FROM credit_ledger",BigDecimal.class));assertEquals(balances,db.queryForObject("SELECT SUM(balance) FROM credit_account",BigDecimal.class));assertEquals(44,number("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL"));assertEquals(0,number("SELECT COUNT(*) FROM flyway_schema_history WHERE version='45'"));
    }
    @Test @Order(5) void explicit41OrLatestCannotMigrateAnUnapprovedCore40EvenWithTheWriterEnabled()throws Exception{
        java.util.function.Supplier<Map<String,String>> snapshot=()->{
            var result=new TreeMap<String,String>();for(String table:List.of("flyway_schema_history","credit_ledger","credit_account","payment_order","payment_webhook_inbox","shared_download_entitlement")){
                var rows=new ArrayList<String>();db.query("SELECT * FROM "+table,(org.springframework.jdbc.core.RowCallbackHandler)rs->{var row=new StringBuilder();for(int i=1;i<=rs.getMetaData().getColumnCount();i++){String value=rs.getString(i);if(value==null)row.append("N;");else row.append('S').append(value.length()).append(':').append(value);}rows.add(row.toString());});Collections.sort(rows);var canonical=new StringBuilder();for(String row:rows)canonical.append(row.length()).append(':').append(row);
                try{result.put(table,"rows="+rows.size()+";sha256="+HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
            }
            result.put("journal_sum",db.queryForObject("SELECT COALESCE(SUM(amount),0) FROM credit_ledger",BigDecimal.class).toPlainString());result.put("balance_sum",db.queryForObject("SELECT COALESCE(SUM(balance),0) FROM credit_account",BigDecimal.class).toPlainString());result.put("table_count",Long.toString(number("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()")));result.put("trigger_count",Long.toString(number("SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()")));return result;
        };
        var before=snapshot.get();assertEquals(44,number("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL"));
        for(String target:List.of("45","latest")){
            var attempted=new LinkedHashMap<>(settings);attempted.put("spring.flyway.target",target);attempted.put("qingfeng.credits.writer-fence.enabled",true);attempted.put("qingfeng.credits.writer-fence.expected-database",database);attempted.put("qingfeng.credits.writer-fence.expected-port",23316);var ready=new java.util.concurrent.atomic.AtomicBoolean();
            var rejected=builder(attempted).listeners((org.springframework.context.ApplicationListener<org.springframework.boot.context.event.ApplicationReadyEvent>)event->ready.set(true));
            RuntimeException failure=assertThrows(RuntimeException.class,()->{try(var unsafe=rejected.run()){fail("Startup must not perform the separately approved financial cutover");}});boolean migrationGate=false;for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(cause.getMessage()!=null&&cause.getMessage().contains("Financial V45 requires separately approved migration, historical mapping and activation before application startup"))migrationGate=true;
            assertTrue(migrationGate,"Must reject at the pre-migration activation gate, not an unrelated startup failure");assertFalse(ready.get());assertTrue(application.isActive());assertEquals(before,snapshot.get());assertEquals(0,number("SELECT COUNT(*) FROM flyway_schema_history WHERE version='45'"));assertEquals(0,number("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND LEFT(TABLE_NAME,3)='p0_'"));assertEquals(0,number("SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND LEFT(TRIGGER_NAME,3)='p0_'"));
            var health=request("/actuator/health","GET",null,null,null);assertEquals(200,health.statusCode());assertEquals("UP",JSON.readTree(health.body()).path("status").asString());
        }
    }
}
