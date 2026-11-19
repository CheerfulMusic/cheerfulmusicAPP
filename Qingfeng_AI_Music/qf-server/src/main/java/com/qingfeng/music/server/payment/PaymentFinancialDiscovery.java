package com.qingfeng.music.server.payment;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Provider reads discover work; the existing leased inbox is the only financial writer. */
@Component
@Profile({"mysql","test"})
@ConditionalOnProperty(name={"qingfeng.payment.inbox.scheduler-enabled","qingfeng.payment.discovery.enabled"},havingValue="true")
public final class PaymentFinancialDiscovery {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(PaymentFinancialDiscovery.class);
    private final StripePaymentService stripe;
    private final StripeCheckoutGateway stripeApi;
    private final PayPalPaymentService paypal;
    private final JdbcTemplate db;
    private final int historyDays;
    private final ExecutorService readers=Executors.newFixedThreadPool(2);
    private final AtomicBoolean stripeBusy=new AtomicBoolean(), paypalBusy=new AtomicBoolean();
    private final Clock clock=Clock.systemUTC();

    public PaymentFinancialDiscovery(StripePaymentService stripe, StripeCheckoutGateway stripeApi,
            PayPalPaymentService paypal, JdbcTemplate db,
            @Value("${qingfeng.payment.discovery.history-days:7}") int historyDays) {
        if(historyDays<1 || historyDays>28)throw new IllegalArgumentException("Payment history must be 1..28 days");
        this.stripe=stripe;this.stripeApi=stripeApi;this.paypal=paypal;this.db=db;this.historyDays=historyDays;
    }

    @Scheduled(fixedDelayString="${qingfeng.payment.discovery.scan-ms:15000}")
    public void tick(){
        submit(stripeBusy,this::scanStripe);
        submit(paypalBusy,this::scanPayPal);
    }

    private void submit(AtomicBoolean busy,Runnable task){
        if(!busy.compareAndSet(false,true))return;
        try {
            readers.execute(()->{
                try { task.run(); }
                catch(RuntimeException failure){log.warn("Payment discovery unavailable: {}",failure.getClass().getSimpleName());}
                finally {busy.set(false);}
            });
        } catch(java.util.concurrent.RejectedExecutionException stopped){busy.set(false);}
    }

    @jakarta.annotation.PreDestroy void stop(){readers.shutdownNow();}

    void scanStripe(){
        if(!stripe.schemaReady())return;
        for(String stream:new String[]{"events","refunds","disputes"})
            scan(new Scope(stripe.provider(),stripeApi.merchantId(),stripeApi.environment(),stream),60,21600,
                    (start,end,token)->stripePage(stream,start,end,token));
    }
    void scanPayPal(){
        if(!paypal.discoveryReady())return;
        scan(new Scope(paypal.provider(),paypal.discoveryMerchant(),paypal.discoveryEnvironment(),"report-refunds"),
                3*3600+300,600,this::paypalRefunds);
        scan(new Scope(paypal.provider(),paypal.discoveryMerchant(),paypal.discoveryEnvironment(),"dispute-updates"),
                60,21600,this::paypalDisputes);
    }
    record Scope(String provider,String merchant,String environment,String stream){}
    record Cursor(long start,long end,String token,long revision){}
    @FunctionalInterface interface ReadPage { String read(long start,long end,String token); }

    private void scan(Scope scope,int lag,int width,ReadPage reader){
        long cutoff=clock.instant().getEpochSecond()-lag;
        try{
            long remaining=advance(scope,cutoff,width,reader);
            if(remaining>3600)log.warn("payment_discovery_backlog provider={} stream={} lag_seconds={}",scope.provider(),scope.stream(),remaining);
        }catch(RuntimeException failed){
            log.warn("payment_discovery_incomplete provider={} stream={} error={}",scope.provider(),scope.stream(),failed.getClass().getSimpleName());
        }
    }

    long advance(Scope s,long cutoff,int width,ReadPage reader){
        db.update("INSERT INTO payment_reconciliation_cursor(provider,merchant_scope,env_scope,stream,window_start,window_end,page_token) VALUES(?,?,?,?,?,0,'') ON DUPLICATE KEY UPDATE revision=revision",
                s.provider,s.merchant,s.environment,s.stream,cutoff-historyDays*86400L);
        long start=0;
        for(int page=0;page<4;page++){
            Cursor c=db.queryForObject("SELECT window_start,window_end,page_token,revision FROM payment_reconciliation_cursor WHERE provider=? AND merchant_scope=? AND env_scope=? AND stream=?",
                    (rs,n)->new Cursor(rs.getLong(1),rs.getLong(2),rs.getString(3),rs.getLong(4)),s.provider,s.merchant,s.environment,s.stream);
            start=c.start;
            if(start>=cutoff)break;
            if(s.stream.equals("events") && start<cutoff-29L*86400){
                // Provider event retention is finite. Keep an explicit audit gap and
                // resume inside retention so a prolonged outage cannot suppress new changes.
                long resume=cutoff-29L*86400;
                if(db.update("UPDATE payment_reconciliation_cursor SET retention_gap_start=COALESCE(retention_gap_start,window_start),retention_gap_end=?,window_start=?,window_end=0,page_token='',revision=revision+1 WHERE provider=? AND merchant_scope=? AND env_scope=? AND stream=? AND revision=?",
                        resume,resume,s.provider,s.merchant,s.environment,s.stream,c.revision)==1)
                    log.error("payment_discovery_retention_gap provider={} from={} until={}",s.provider,start,resume);
                continue;
            }
            long end=c.end==0?Math.min(cutoff,start+width):c.end;
            // Re-read the seam. A page is checkpointed only after every inbox insert has committed.
            String next=reader.read(start-60,end,c.token);
            if(next.length()>2048 || !next.isEmpty() && next.equals(c.token))throw new IllegalStateException("Invalid next page");
            long nextStart=next.isEmpty()?end:start;
            if(db.update("UPDATE payment_reconciliation_cursor SET window_start=?,window_end=?,page_token=?,revision=revision+1 WHERE provider=? AND merchant_scope=? AND env_scope=? AND stream=? AND revision=?",
                    nextStart,next.isEmpty()?0:end,next,s.provider,s.merchant,s.environment,s.stream,c.revision)!=1)break;
            start=nextStart;
        }
        return Math.max(0,cutoff-start);
    }

    private String stripePage(String stream,long start,long end,String cursor){
        JsonNode page=stripeApi.financialPage(stream,start,end,cursor);
        if(!"list".equals(text(page,"object")) || !page.path("data").isArray() || page.path("data").size()>100
                || !page.path("has_more").isBoolean())throw new IllegalStateException("Incomplete Stripe listing");
        String last="";
        Set<String> seen=new HashSet<>();
        for(JsonNode item:page.path("data")){
            last=text(item,"id");
            // Refund summaries may omit livemode. Their scope is established by the
            // authenticated merchant GET; events and disputes also carry an explicit mode.
            if(!stream.equals("refunds") || item.has("livemode")){
                if(!item.path("livemode").isBoolean() || item.path("livemode").asBoolean()!=stripeApi.isLive())
                    throw new IllegalStateException("Invalid financial resource mode");
            }
            if(!seen.add(last) || last.equals(cursor))throw new IllegalStateException("Invalid Stripe listing cursor");
            if(stream.equals("events")){
                if(!last.matches("evt_[A-Za-z0-9_]{1,124}") || !"event".equals(text(item,"object")))throw new IllegalStateException("Invalid Stripe event");
                if(item.has("account") && !item.path("account").isNull() && !stripeApi.merchantId().equals(text(item,"account")))throw new IllegalStateException("Unexpected event account");
                String type=text(item,"type");
                if(!type.startsWith("refund.") && !type.startsWith("charge.dispute."))throw new IllegalStateException("Unexpected financial event");
                stripe.recordPointer(last,type,text(item.path("data").path("object"),"id"));
            }else{
                boolean refund=stream.equals("refunds");
                if(!last.matches(refund?"re_[A-Za-z0-9_]{1,125}":"(?:dp|du)_[A-Za-z0-9_]{1,125}") || !(refund?"refund":"dispute").equals(text(item,"object")))
                    throw new IllegalStateException("Invalid financial resource");
                stripe.recordPointer(id(stream,last,text(item,"status")),refund?"refund.updated":"charge.dispute.updated",last);
            }
        }
        if(page.path("has_more").asBoolean() && last.isEmpty())throw new IllegalStateException("Missing continuation");
        return page.path("has_more").asBoolean()?last:"";
    }

    private String paypalRefunds(long start,long end,String unused){
        JsonNode report=paypal.gateway().refundReport(Instant.ofEpochSecond(start),Instant.ofEpochSecond(end));
        if(!paypal.discoveryMerchant().equals(text(report,"account_number")) || !report.path("complete").asBoolean(false)
                || !report.path("transaction_details").isArray() || Instant.parse(text(report,"end_date")).getEpochSecond()!=end
                || Instant.parse(text(report,"last_refreshed_datetime")).getEpochSecond()<end)throw new IllegalStateException("Incomplete refund report");
        for(JsonNode row:report.path("transaction_details")){
            JsonNode info=row.path("transaction_info");
            String refund=text(info,"transaction_id");
            if(!"T1107".equals(text(info,"transaction_event_code")) || !refund.matches("[A-Za-z0-9]{1,32}"))throw new IllegalStateException("Unexpected refund transaction");
            paypal.recordDiscovered(id("refund",refund,text(info,"transaction_updated_date")),"PAYMENT.CAPTURE.REFUNDED",refund);
        }
        return "";
    }

    private String paypalDisputes(long start,long end,String cursor){
        JsonNode page=paypal.gateway().disputeUpdates(start,end,cursor);
        if(!page.path("items").isArray() || page.path("items").size()>50 || !page.path("links").isArray())
            throw new IllegalStateException("Incomplete disputes page");
        for(JsonNode item:page.path("items")){
            String dispute=text(item,"dispute_id"),updated=text(item,"update_time");
            if(!dispute.matches("[A-Za-z0-9-]{1,128}") || updated.isBlank())throw new IllegalStateException("Invalid dispute summary");
            paypal.recordDiscovered(id("dispute",dispute,updated),"CUSTOMER.DISPUTE.UPDATED",dispute);
        }
        String next="";
        for(JsonNode link:page.path("links"))if("next".equals(text(link,"rel"))){
            String query=URI.create(text(link,"href")).getRawQuery();
            if(query==null || !next.isEmpty())throw new IllegalStateException("Invalid disputes continuation");
            for(String part:query.split("&"))if(part.startsWith("next_page_token=")){
                if(!next.isEmpty())throw new IllegalStateException("Duplicate token");
                next=URLDecoder.decode(part.substring("next_page_token=".length()),StandardCharsets.UTF_8);
            }
            if(next.isBlank())throw new IllegalStateException("Missing disputes continuation");
        }
        return next;
    }

    private static String id(String stream,String resource,String version){
        return "scan-"+UUID.nameUUIDFromBytes((stream+"|"+resource+"|"+version).getBytes(StandardCharsets.UTF_8));
    }
    private static String text(JsonNode node,String field){return node.path(field).asString("");}
}
