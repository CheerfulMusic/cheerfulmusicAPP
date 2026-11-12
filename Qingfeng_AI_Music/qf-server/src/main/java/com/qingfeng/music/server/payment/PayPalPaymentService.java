package com.qingfeng.music.server.payment;

import com.qingfeng.music.credits.CreditsLedger;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** PayPal payment flow. Remote calls are outside transactions; successful capture and credit commit together. */
@Service @Profile({"mysql","test"})
public class PayPalPaymentService implements VerifiedPaymentProcessor {
    private final JdbcTemplate db; private final TransactionTemplate tx; private final CreditsLedger ledger; private final PaymentCatalog catalog;
    @Value("${qingfeng.paypal.enabled:false}") private boolean enabled;
    @Value("${qingfeng.paypal.accepting-new-orders:true}") private boolean acceptingNewOrders = true;
    @Value("${qingfeng.paypal.environment:sandbox}") private String environment;
    @Value("${qingfeng.paypal.client-id:}") private String clientId;
    @Value("${qingfeng.paypal.client-secret:}") private String clientSecret;
    @Value("${qingfeng.paypal.merchant-id:}") private String merchantId;
    @Value("${qingfeng.paypal.webhook-id:}") private String webhookId;
    @Value("${qingfeng.paypal.live-enabled:false}") private boolean liveEnabled;
    @Value("${qingfeng.paypal.public-live-enabled:false}") private boolean publicLiveEnabled;
    @Value("${qingfeng.paypal.return-base-url:https://cheerfulmusic.com/account}") private String returnBase;
    private final Environment configuration;
    private volatile PayPalCheckoutGateway sharedGateway;
    private PaymentInbox inbox;
    private PaymentRiskService risks;
    @Value("${qingfeng.payment.inbox.enabled:true}") private boolean inboxEnabled=true;
    @org.springframework.beans.factory.annotation.Autowired
    void infrastructure(PaymentInbox inbox,PaymentRiskService risks){this.inbox=inbox;this.risks=risks;}
    public PayPalPaymentService(JdbcTemplate db,TransactionTemplate tx,CreditsLedger ledger,PaymentCatalog catalog,Environment configuration){this.db=db;this.tx=tx;this.ledger=ledger;this.catalog=catalog;this.configuration=configuration;}
    /** Local official PayPal Live checkout: popup-only, no public webhook, isolated on the 3309 database. */
    boolean localLive(){return environment.equals("live") && liveEnabled
        && Arrays.asList(configuration.getActiveProfiles()).contains("paypal-live")
        && !Arrays.asList(configuration.getActiveProfiles()).contains("payment-demo")
        && "paypal-local".equals(configuration.getProperty("qingfeng.deployment.environment"))
        && "http://127.0.0.1:5173/account".equals(returnBase);}
    /** Public Live checkout on the production site. Requires a second, independent opt-in so that
     *  the local paypal-live configuration can never accidentally enable real charges on the server.
     *  Webhook verification is mandatory here (unlike the popup-only local checkout). */
    boolean publicLive(){return environment.equals("live") && liveEnabled && publicLiveEnabled
        && !webhookId.isBlank()
        && "https://cheerfulmusic.com/account".equals(returnBase)
        && !Arrays.asList(configuration.getActiveProfiles()).contains("paypal-live")
        && !Arrays.asList(configuration.getActiveProfiles()).contains("payment-demo")
        && !"paypal-local".equals(configuration.getProperty("qingfeng.deployment.environment"));}
    // Verified refunds/disputes enter a durable inbox and freeze spending pending an audited review.
    // Public Live checkout still requires explicit merchant acceptance and its independent opt-in.
    boolean ready(){return enabled && !clientId.isBlank() && !clientSecret.isBlank() && !merchantId.isBlank()
        && ((environment.equals("sandbox") && !webhookId.isBlank()) || localLive() || publicLive());}
    @Override public String provider(){return environment.equals("live")?"PAYPAL_LIVE":"PAYPAL_SANDBOX";}
    PayPalCheckoutGateway gateway(){
        var current=sharedGateway;if(current!=null)return current;
        synchronized(this){if(sharedGateway==null)sharedGateway=new PayPalCheckoutGateway(environment.equals("live")?PayPalCheckoutGateway.Environment.LIVE:PayPalCheckoutGateway.Environment.SANDBOX,clientId,clientSecret,merchantId,webhookId,localLive());return sharedGateway;}
    }
    public Map<String,Object> catalog(){return Map.of("enabled",ready() && acceptingNewOrders,"settlementEnabled",ready(),"environment",environment.equals("live")?"live":"sandbox","popupOnly",localLive(),"packages",ready()?catalog.packages().stream().map(PaymentCatalog.Package::asMap).toList():List.of());}
    /** The client ID is public; no server-side secret is returned to the browser. */
    public Map<String,Object> sdkConfig(){return Map.of("enabled",ready(),"clientId",ready()?clientId:"","environment",environment.equals("live")?"production":"sandbox");}
    public Map<String,Object> create(String owner,String packageId){
        if(!ready()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"PayPal 尚未开放");
        if(!acceptingNewOrders) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"新购买暂时暂停，已有订单仍可核对");
        var p=catalog.find(packageId).orElseThrow(()->new ResponseStatusException(HttpStatus.BAD_REQUEST,"套餐不存在"));
        String id=UUID.randomUUID().toString(), type=provider();
        tx.executeWithoutResult(s->{
            if(db.queryForList("SELECT id FROM creator WHERE id=? FOR UPDATE",owner).isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
            Integer identity=db.queryForObject("SELECT (SELECT COUNT(*) FROM creator_google_identity WHERE creator_id=?) + (SELECT COUNT(*) FROM creator_oidc_identity WHERE creator_id=?)",Integer.class,owner,owner);
            if(identity==null||identity==0) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"请先登录账号");
            Integer pending=db.queryForObject("SELECT COUNT(*) FROM payment_order WHERE creator_id=? AND status IN ('CREATING','CREATION_UNKNOWN','PENDING')",Integer.class,owner);
            if(pending!=null && pending>0) throw new ResponseStatusException(HttpStatus.CONFLICT,"已有待核对的订单");
            db.update("INSERT INTO payment_order(id,creator_id,package_id,amount,currency,credits,status,created_at,provider) VALUES(?,?,?,?,?,?,?,CURRENT_TIMESTAMP,?)",id,owner,p.id(),p.amount(),p.currency(),p.credits(),"CREATING",type);
        });
        PayPalCheckoutGateway.Created link;
        try{link=gateway().create(id,p.title(),p.amount(),p.currency(),returnBase+"?paypal_order="+id,returnBase+"?paypal_cancel="+id);}
        catch(PayPalCheckoutGateway.ProviderBusy busy){
            db.update("UPDATE payment_order SET status='FAILED',failure_code='CREATE_NOT_STARTED' WHERE id=? AND status='CREATING' AND provider_link_id IS NULL",id);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"支付通道繁忙，本次尚未创建支付单，请稍后重试");
        }
        catch(PayPalCheckoutGateway.CreationRejected rejected){
            db.update("UPDATE payment_order SET status='FAILED',failure_code='CREATE_REJECTED' WHERE id=? AND status='CREATING' AND provider_link_id IS NULL",id);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"支付平台明确拒绝创建订单，可稍后重试");
        }catch(RuntimeException unknown){
            db.update("UPDATE payment_order SET status='CREATION_UNKNOWN',failure_code='CREATE_RESULT_UNKNOWN' WHERE id=? AND status='CREATING' AND provider_link_id IS NULL",id);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"订单创建结果待核查，请保留原订单并联系支持核对，请勿重复付款");
        }
        db.update("UPDATE payment_order SET provider_link_id=?,status=CASE WHEN status='CREATING' THEN 'PENDING' ELSE status END WHERE id=? AND provider=? AND (provider_link_id IS NULL OR provider_link_id=?)",link.orderId(),id,type,link.orderId());
        return Map.of("orderId",id,"checkoutUrl",link.approvalUrl(),"paypalOrderId",link.orderId());
    }
    Map<String,Object> row(String owner,String id){
        var rows=db.queryForList("SELECT * FROM payment_order WHERE id=? AND creator_id=? AND provider=?",id,owner,provider());
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND); return rows.get(0);
    }
    /** Attach an existing provider order after an uncertain create; this never creates or captures an order. */
    public Map<String,Object> recover(String owner,String id,String remote){
        if(!ready())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        var original=row(owner,id);
        var snapshot=gateway().retrieve(remote);
        tx.executeWithoutResult(t->{
            db.queryForList("SELECT id FROM creator WHERE id=? FOR UPDATE",owner);
            var locked=db.queryForMap("SELECT * FROM payment_order WHERE id=? AND creator_id=? AND provider=? FOR UPDATE",id,owner,provider());
            String prior=(String)locked.get("provider_link_id");
            if(prior!=null&&!prior.equals(remote))throw new ResponseStatusException(HttpStatus.CONFLICT,"原支付单不匹配");
            if(!Set.of("CREATING","CREATION_UNKNOWN","PENDING","PAID").contains(locked.get("status")))throw new ResponseStatusException(HttpStatus.CONFLICT,"订单不能恢复");
            var expected=new HashMap<>(locked);expected.put("provider_link_id",remote);check(expected,snapshot);
            db.update("UPDATE payment_order SET provider_link_id=?,status=CASE WHEN status IN ('CREATING','CREATION_UNKNOWN') THEN 'PENDING' ELSE status END,failure_code=NULL WHERE id=?",remote,id);
            if("COMPLETED".equals(snapshot.orderStatus())&&"COMPLETED".equals(snapshot.captureStatus()))settle(id,snapshot);
        });
        return order(owner,id);
    }
    public Map<String,Object> order(String owner,String id){
        var r=row(owner,id);
        String remote=(String)r.get("provider_link_id");
        if(ready() && !"PAID".equals(r.get("status")) && remote!=null){
            PayPalCheckoutGateway.Snapshot snapshot;
            try { snapshot=gateway().retrieve(remote); }
            catch (RuntimeException ex) { return Map.of("orderId",id,"status",r.get("status"),"credits",r.get("credits")); }
            // Query only: never capture an approved order without an explicit user action.
            check(r,snapshot);
            if("COMPLETED".equals(snapshot.orderStatus()) && "COMPLETED".equals(snapshot.captureStatus())){
                settle(id,snapshot);
                r=row(owner,id);
            }
        }
        return Map.of("orderId",id,"status",r.get("status"),"credits",r.get("credits"));
    }
    public Map<String,Object> orders(String owner){return Map.of("orders",db.query("SELECT id,status,credits,created_at FROM payment_order WHERE creator_id=? AND provider=? ORDER BY created_at DESC,id DESC LIMIT 50",(rs,n)->Map.of("orderId",rs.getString(1),"status",rs.getString(2),"credits",rs.getLong(3),"createdAt",rs.getTimestamp(4).toInstant().toString()),owner,provider()));}
    public Map<String,Object> close(String owner,String id){
        var r=row(owner,id);
        if("PAID".equals(r.get("status")) || "CANCELLED".equals(r.get("status"))) return order(owner,id);
        String remote=(String)r.get("provider_link_id");
        if(!ready() || remote==null) throw new ResponseStatusException(HttpStatus.CONFLICT,"订单待核对");
        var snapshot=gateway().retrieve(remote); check(r,snapshot);
        if("COMPLETED".equals(snapshot.orderStatus()) && "COMPLETED".equals(snapshot.captureStatus())) {settle(id,snapshot);return order(owner,id);}
        if("APPROVED".equals(snapshot.orderStatus())) throw new ResponseStatusException(HttpStatus.CONFLICT,"付款已授权，订单待核对");
        if(!List.of("CREATED","PAYER_ACTION_REQUIRED","VOIDED").contains(snapshot.orderStatus())) throw new ResponseStatusException(HttpStatus.CONFLICT,"订单状态待核对");
        db.update("UPDATE payment_order SET status='CANCELLED' WHERE id=? AND creator_id=? AND provider=? AND status='PENDING'",id,owner,provider());
        return Map.of("orderId",id,"status",row(owner,id).get("status"),"credits",r.get("credits"));
    }
    public Map<String,Object> resume(String owner,String id){
        if(!ready()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"PayPal 尚未开放");
        var r=row(owner,id);
        if("PAID".equals(r.get("status"))) return Map.of("orderId",id,"status","PAID");
        if("CANCELLED".equals(r.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT,"订单已取消");
        String remote=(String)r.get("provider_link_id");
        if(remote==null) throw new ResponseStatusException(HttpStatus.CONFLICT,"订单待核对");
        var view=gateway().retrieveCheckout(remote); var snapshot=view.snapshot(); check(r,snapshot);
        if("COMPLETED".equals(snapshot.orderStatus()) && "COMPLETED".equals(snapshot.captureStatus())){
            settle(id,snapshot); return Map.of("orderId",id,"status","PAID");
        }
        if("APPROVED".equals(snapshot.orderStatus())) return Map.of("orderId",id,"status","APPROVED");
        if(("CREATED".equals(snapshot.orderStatus()) || "PAYER_ACTION_REQUIRED".equals(snapshot.orderStatus())) && !view.approvalUrl().isBlank())
            return Map.of("orderId",id,"status","CHECKOUT","checkoutUrl",view.approvalUrl());
        throw new ResponseStatusException(HttpStatus.CONFLICT,"订单状态待核对");
    }
    void check(Map<String,Object> row,PayPalCheckoutGateway.Snapshot s){
        if(!Objects.equals(row.get("provider_link_id"),s.orderId()) || !Objects.equals(row.get("id"),s.reference()) || !Objects.equals(row.get("currency"),s.currency()) || s.amount()==null || ((BigDecimal)row.get("amount")).compareTo(s.amount())!=0 || !merchantId.equals(s.payeeId())) throw new ResponseStatusException(HttpStatus.CONFLICT,"PayPal 订单不匹配");
    }
    public Map<String,Object> capture(String owner,String id){
        if(!ready()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        var r=row(owner,id); if("PAID".equals(r.get("status"))) return order(owner,id);
        String remote=(String)r.get("provider_link_id"); if(remote==null) throw new ResponseStatusException(HttpStatus.CONFLICT,"订单待核对");
        var gateway=gateway(); var before=gateway.retrieve(remote); check(r,before);
        if("COMPLETED".equals(before.orderStatus())) { settle(id,before); return order(owner,id); }
        if("CANCELLED".equals(r.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT,"订单已取消");
        if(!"APPROVED".equals(before.orderStatus())) throw new ResponseStatusException(HttpStatus.CONFLICT,"付款尚未授权");
        var captured=gateway.capture(remote,"c"+id); check(r,captured); settle(id,captured); return order(owner,id);
    }
    void settle(String id,PayPalCheckoutGateway.Snapshot s){
        tx.executeWithoutResult(t->{
            String owner=db.queryForObject("SELECT creator_id FROM payment_order WHERE id=? AND provider=?",String.class,id,provider());
            db.queryForList("SELECT id FROM creator WHERE id=? FOR UPDATE",owner);
            var rows=db.queryForList("SELECT * FROM payment_order WHERE id=? AND provider=? FOR UPDATE",id,provider());
            if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            var row=rows.get(0);check(row,s);
            if(!"COMPLETED".equals(s.orderStatus()) || !"COMPLETED".equals(s.captureStatus()) || s.captureId()==null || s.captureId().isBlank()) throw new ResponseStatusException(HttpStatus.CONFLICT,"PayPal 付款尚未完成");
            Object old=row.get("provider_capture_id"); if(old!=null&&!old.equals(s.captureId())) throw new ResponseStatusException(HttpStatus.CONFLICT,"支付记录不匹配");
            ledger.credit((String)row.get("creator_id"),((Number)row.get("credits")).longValue(),"payment:"+id);
            db.update("UPDATE payment_order SET status='PAID',provider_capture_id=? WHERE id=?",s.captureId(),id);
        });
    }
    public void webhook(PayPalCheckoutGateway.WebhookHeaders headers,byte[] body){
        if(!ready() || webhookId.isBlank()||!inboxEnabled||inbox==null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        var gateway=gateway();
        if(!gateway.verifyWebhook(headers,body)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        var event=new tools.jackson.databind.ObjectMapper().readTree(body);
        String eventId=event.path("id").asString(),type=event.path("event_type").asString();
        var resource=event.path("resource");
        // Store only identifiers needed for authoritative rereads, not payer details or callback credentials.
        var normalized=new TreeMap<String,String>();
        String resourceId=resource.path("id").asString();
        if(resourceId.isBlank())resourceId=resource.path("dispute_id").asString();
        normalized.put("resourceId",resourceId);
        normalized.put("orderId",resource.path("supplementary_data").path("related_ids").path("order_id").asString());
        inbox.acceptVerified(provider(),eventId,type,new tools.jackson.databind.ObjectMapper().writeValueAsString(normalized));
    }
    public boolean processNextEvent(){
        if(!ready()||!inboxEnabled||inbox==null)return false;
        var found=inbox.claim(provider());if(found.isEmpty())return false;
        var event=found.get();
        try{
            var payload=new tools.jackson.databind.ObjectMapper().readTree(event.payload());
            String resource=payload.path("resourceId").asString();
            if("PAYMENT.CAPTURE.COMPLETED".equals(event.type())){
                String remote=payload.path("orderId").asString();
                if(remote.isBlank())remote=gateway().retrieveCapture(resource).path("supplementary_data").path("related_ids").path("order_id").asString();
                var snapshot=gateway().retrieve(remote);
                if(!resource.equals(snapshot.captureId()))throw new IllegalStateException("Capture correlation mismatch");
                String order=snapshot.reference();
                var local=db.queryForMap("SELECT * FROM payment_order WHERE id=? AND provider=?",order,provider());
                var expected=new HashMap<>(local);expected.put("provider_link_id",snapshot.orderId());check(expected,snapshot);
                if(local.get("provider_link_id")!=null&&!snapshot.orderId().equals(local.get("provider_link_id")))throw new IllegalStateException("Provider order mismatch");
                inbox.complete(event,()->{
                    db.queryForList("SELECT id FROM creator WHERE id=? FOR UPDATE",local.get("creator_id"));
                    db.update("UPDATE payment_order SET provider_link_id=?,failure_code=NULL WHERE id=? AND provider=? AND provider_link_id IS NULL",snapshot.orderId(),order,provider());
                    if(Set.of("REFUNDED","PARTIALLY_REFUNDED","REVERSED").contains(snapshot.captureStatus())){
                        db.update("UPDATE payment_order SET provider_capture_id=? WHERE id=? AND (provider_capture_id IS NULL OR provider_capture_id=?)",snapshot.captureId(),order,snapshot.captureId());
                        risks.recordVerified(provider(),snapshot.captureId(),"PAYMENT.CAPTURE.REFUND_DETECTED",order,event.eventId());
                    }else settle(order,snapshot);
                });
            }else if("PAYMENT.CAPTURE.REFUNDED".equals(event.type())||"PAYMENT.CAPTURE.REVERSED".equals(event.type())||"PAYMENT.CAPTURE.STATUS_RISK".equals(event.type())||event.type().startsWith("CUSTOMER.DISPUTE.")){
                var captures=new LinkedHashSet<String>();
                if(event.type().startsWith("CUSTOMER.DISPUTE.")){
                    var dispute=gateway().retrieveDispute(resource);
                    if(!resource.equals(dispute.path("dispute_id").asString()))throw new IllegalStateException("Dispute identity mismatch");
                    for(var transaction:dispute.path("disputed_transactions")){String capture=transaction.path("seller_transaction_id").asString();if(!capture.isBlank())captures.add(capture);}
                }else if("PAYMENT.CAPTURE.REVERSED".equals(event.type())||"PAYMENT.CAPTURE.STATUS_RISK".equals(event.type())){
                    var capture=gateway().retrieveCapture(resource);if(!resource.equals(capture.path("id").asString()))throw new IllegalStateException("Capture identity mismatch");
                    if("PAYMENT.CAPTURE.STATUS_RISK".equals(event.type())&&!Set.of("REFUNDED","PARTIALLY_REFUNDED","REVERSED").contains(capture.path("status").asString())){
                        inbox.complete(event,()->{});return true;
                    }
                    captures.add(resource);
                }else{
                    var refund=gateway().retrieveRefund(resource);if(!resource.equals(refund.path("id").asString()))throw new IllegalStateException("Refund identity mismatch");
                    for(var link:refund.path("links"))if("up".equals(link.path("rel").asString())){
                        var uri=java.net.URI.create(link.path("href").asString());String prefix="/v2/payments/captures/";
                        if("https".equals(uri.getScheme())&&Set.of("api-m.paypal.com","api-m.sandbox.paypal.com","api.paypal.com","api.sandbox.paypal.com").contains(uri.getHost())&&uri.getPath().startsWith(prefix))captures.add(uri.getPath().substring(prefix.length()));
                    }
                }
                if(captures.isEmpty()||captures.size()>20)throw new IllegalStateException("Risk resource has no bounded payment correlation");
                var orders=new TreeSet<String>();
                var recovered=new LinkedHashMap<String,PayPalCheckoutGateway.Snapshot>();
                for(String capture:captures){
                    inbox.renew(event);
                    var matches=db.query("SELECT id FROM payment_order WHERE provider=? AND provider_capture_id=?",(rs,n)->rs.getString(1),provider(),capture);
                    if(matches.size()==1){orders.add(matches.get(0));continue;}
                    if(!matches.isEmpty())throw new IllegalStateException("Ambiguous risk payment");
                    // Refund/dispute can arrive before the completion event. Correlate without granting credits.
                    var captured=gateway().retrieveCapture(capture);
                    if(!capture.equals(captured.path("id").asString()))throw new IllegalStateException("Capture identity mismatch");
                    String remote=captured.path("supplementary_data").path("related_ids").path("order_id").asString();
                    var snapshot=gateway().retrieve(remote);
                    if(!capture.equals(snapshot.captureId()))throw new IllegalStateException("Capture/order mismatch");
                    var local=db.queryForMap("SELECT * FROM payment_order WHERE id=? AND provider=?",snapshot.reference(),provider());
                    var expected=new HashMap<>(local);expected.put("provider_link_id",snapshot.orderId());check(expected,snapshot);
                    if(local.get("provider_link_id")!=null&&!snapshot.orderId().equals(local.get("provider_link_id")))throw new IllegalStateException("Provider order mismatch");
                    orders.add(snapshot.reference());recovered.put(snapshot.reference(),snapshot);
                }
                var owners=new TreeSet<String>();
                for(String order:orders)owners.add(db.queryForObject("SELECT creator_id FROM payment_order WHERE id=? AND provider=?",String.class,order,provider()));
                inbox.complete(event,()->{
                    for(String owner:owners)db.queryForList("SELECT id FROM creator WHERE id=? FOR UPDATE",owner);
                    for(var entry:recovered.entrySet()){
                        var snapshot=entry.getValue();
                        db.update("UPDATE payment_order SET provider_link_id=?,provider_capture_id=? WHERE id=? AND provider=? AND (provider_link_id IS NULL OR provider_link_id=?) AND (provider_capture_id IS NULL OR provider_capture_id=?)",snapshot.orderId(),snapshot.captureId(),entry.getKey(),provider(),snapshot.orderId(),snapshot.captureId());
                    }
                    for(String order:orders)risks.recordVerified(provider(),resource,event.type(),order,event.eventId());
                });
            }else inbox.complete(event,()->{});
        }catch(RuntimeException failed){inbox.retry(event);}
        return true;
    }
    /** Bounded, read-only provider reconciliation for existing linked orders; never captures or recreates. */
    public void reconcileOrders(){
        if(!ready())return;
        db.update("UPDATE payment_order SET status='CREATION_UNKNOWN',failure_code='CREATE_PROCESS_INTERRUPTED' WHERE provider=? AND status='CREATING' AND provider_link_id IS NULL AND created_at<TIMESTAMPADD(MINUTE,-5,CURRENT_TIMESTAMP)",provider());
        var rows=db.queryForList("SELECT id,creator_id FROM payment_order WHERE provider=? AND status IN ('PENDING','CANCELLED') AND provider_link_id IS NOT NULL AND (reconcile_after IS NULL OR reconcile_after<CURRENT_TIMESTAMP) ORDER BY reconcile_after,created_at,id LIMIT 10",provider());
        for(var row:rows){
            if(db.update("UPDATE payment_order SET reconcile_after=TIMESTAMPADD(MINUTE,5,CURRENT_TIMESTAMP) WHERE id=? AND (reconcile_after IS NULL OR reconcile_after<CURRENT_TIMESTAMP)",row.get("id"))!=1)continue;
            try{order((String)row.get("creator_id"),(String)row.get("id"));}catch(RuntimeException ignored){/* persisted due time permits a later retry */}
        }
        reconcilePaidCaptures();
    }
    /** Detect a missed refund callback for recent paid orders. Disputes still require their verified webhook. */
    private void reconcilePaidCaptures(){
        if(!inboxEnabled||inbox==null)return;
        var rows=db.queryForList("SELECT id,provider_link_id,provider_capture_id,amount,currency FROM payment_order WHERE provider=? AND status='PAID' AND provider_capture_id IS NOT NULL AND created_at>=TIMESTAMPADD(DAY,-30,CURRENT_TIMESTAMP) AND (reconcile_after IS NULL OR reconcile_after<CURRENT_TIMESTAMP) ORDER BY reconcile_after,created_at,id LIMIT 10",provider());
        for(var row:rows){
            if(db.update("UPDATE payment_order SET reconcile_after=TIMESTAMPADD(MINUTE,5,CURRENT_TIMESTAMP) WHERE id=? AND provider=? AND status='PAID' AND (reconcile_after IS NULL OR reconcile_after<CURRENT_TIMESTAMP)",row.get("id"),provider())!=1)continue;
            try{
                String id=(String)row.get("provider_capture_id");var capture=gateway().retrieveCapture(id);
                if(!id.equals(capture.path("id").asString()))throw new IllegalStateException("Capture identity mismatch");
                String remote=capture.path("supplementary_data").path("related_ids").path("order_id").asString();
                if(!remote.isBlank()&&!Objects.equals(remote,row.get("provider_link_id")))throw new IllegalStateException("Capture order mismatch");
                if(!Objects.equals(row.get("currency"),capture.path("amount").path("currency_code").asString())||((BigDecimal)row.get("amount")).compareTo(new BigDecimal(capture.path("amount").path("value").asString()))!=0)throw new IllegalStateException("Capture amount mismatch");
                String status=capture.path("status").asString();
                if(!Set.of("REFUNDED","PARTIALLY_REFUNDED","REVERSED").contains(status))continue;
                String eventId="reconcile-"+UUID.nameUUIDFromBytes((provider()+":"+id+":"+status).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                String payload=new tools.jackson.databind.ObjectMapper().writeValueAsString(new TreeMap<>(Map.of("resourceId",id,"orderId",Objects.toString(row.get("provider_link_id"),""))));
                // Stable provider/capture/status identity prevents a recurring scan from reopening a reviewed case.
                inbox.acceptVerified(provider(),eventId,"PAYMENT.CAPTURE.STATUS_RISK",payload);
            }catch(RuntimeException ignored){/* persisted due time permits a later retry; no balance adjustment */}
        }
    }
}
