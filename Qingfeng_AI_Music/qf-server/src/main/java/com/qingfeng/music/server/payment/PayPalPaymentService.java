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
    @Value("${qingfeng.paypal.canary-owner-ids:}") private String canaryOwnerIds = "";
    @Value("${qingfeng.payment.legacy-orders-read-only:false}") private boolean legacyOrdersReadOnly;
    @Value("${qingfeng.paypal.environment:sandbox}") private String environment;
    @Value("${qingfeng.paypal.client-id:}") private String clientId;
    @Value("${qingfeng.paypal.client-secret:}") private String clientSecret;
    @Value("${qingfeng.paypal.merchant-id:}") private String merchantId;
    @Value("${qingfeng.paypal.webhook-id:}") private String webhookId;
    @Value("${qingfeng.paypal.live-enabled:false}") private boolean liveEnabled;
    @Value("${qingfeng.paypal.public-live-enabled:false}") private boolean publicLiveEnabled;
    @Value("${qingfeng.paypal.return-base-url:https://cheerfulmusic.com/account}") private String returnBase;
    private final Environment configuration;
    private final PaymentAcceptanceOffers acceptanceOffers;
    private volatile PayPalCheckoutGateway sharedGateway;
    private PaymentInbox inbox;
    private PaymentRiskService risks;
    @Value("${qingfeng.payment.inbox.enabled:true}") private boolean inboxEnabled=true;
    @org.springframework.beans.factory.annotation.Autowired
    void infrastructure(PaymentInbox inbox,PaymentRiskService risks){this.inbox=inbox;this.risks=risks;}
    public PayPalPaymentService(JdbcTemplate db,TransactionTemplate tx,CreditsLedger ledger,PaymentCatalog catalog,Environment configuration){this.db=db;this.tx=tx;this.ledger=ledger;this.catalog=catalog;this.configuration=configuration;this.acceptanceOffers=new PaymentAcceptanceOffers(configuration);}
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
    public Map<String,Object> catalog(){return catalog(null);}
    public Map<String,Object> catalog(com.qingfeng.music.server.security.CreatorPrincipal principal){
        boolean open=ready()&&purchaseAllowed(principal==null?null:principal.id());
        var packages=new ArrayList<Map<String,Object>>(ready()?catalog.packages().stream().map(PaymentCatalog.Package::asMap).toList():List.of());
        boolean privateAcceptance=!acceptingNewOrders&&principal!=null&&acceptanceOffers.configuredFor(principal.id());
        if(privateAcceptance)packages.clear();
        if(open&&privateAcceptance)acceptanceOffers.offer(principal.id(),"paypal").filter(pack->acceptanceOffers.unused(db,principal.id(),pack.id(),provider()))
            .ifPresent(pack->packages.add(acceptanceOffers.view(pack,"paypal")));
        return Map.of("enabled",open,"settlementEnabled",ready(),"environment",environment.equals("live")?"live":"sandbox","popupOnly",localLive(),"packages",List.copyOf(packages));
    }
    /** A closed public gate may admit at most three exact authenticated owner IDs.
     * Existing-order settlement never consults this new-purchase-only exception. */
    private boolean purchaseAllowed(String owner){
        if(acceptingNewOrders)return true;
        if(owner==null||canaryOwnerIds==null||canaryOwnerIds.isBlank())return false;
        String[] configured=canaryOwnerIds.split(",",-1);
        if(configured.length>3)return false;
        var owners=new HashSet<String>();
        for(String entry:configured){
            String id=entry.trim();
            if(!id.matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")||!owners.add(id))return false;
        }
        return owners.contains(owner);
    }
    /** The client ID is public; no server-side secret is returned to the browser. */
    public Map<String,Object> sdkConfig(){return Map.of("enabled",ready(),"clientId",ready()?clientId:"","environment",environment.equals("live")?"production":"sandbox");}
    public Map<String,Object> create(String owner,String packageId){
        if(!ready()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"PayPal 尚未开放");
        if(!purchaseAllowed(owner)) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"新购买暂时暂停，已有订单仍可核对");
        if(PaymentAcceptanceOffers.isAcceptance(packageId)&&acceptingNewOrders)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"PAYMENT_ACCEPTANCE_CANARY_ONLY");
        if(!acceptingNewOrders&&acceptanceOffers.configuredFor(owner)&&!PaymentAcceptanceOffers.isAcceptance(packageId))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"PAYMENT_ACCEPTANCE_PRIVATE_SKU_REQUIRED");
        var p=PaymentAcceptanceOffers.isAcceptance(packageId)?acceptanceOffers.select(owner,packageId,"paypal")
            :catalog.find(packageId).orElseThrow(()->new ResponseStatusException(HttpStatus.BAD_REQUEST,"套餐不存在"));
        String id=UUID.randomUUID().toString(), type=provider();
        tx.executeWithoutResult(s->{
            if(db.queryForList("SELECT id FROM creator WHERE id=? FOR UPDATE",owner).isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
            Integer identity=db.queryForObject("SELECT (SELECT COUNT(*) FROM creator_google_identity WHERE creator_id=?) + (SELECT COUNT(*) FROM creator_oidc_identity WHERE creator_id=?)",Integer.class,owner,owner);
            if(identity==null||identity==0) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"请先登录账号");
            acceptanceOffers.requireReservation(db,owner,p.id(),type);
            Integer pending;
            if(legacyOrdersReadOnly){
                var boundaries=db.queryForList("SELECT activated_at FROM p0_credit_cutover_state WHERE id=1 AND status='ACTIVE'",java.sql.Timestamp.class);
                if(boundaries.size()!=1||boundaries.get(0)==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"积分切换尚未完成");
                pending=db.queryForObject("SELECT COUNT(*) FROM payment_order WHERE creator_id=? AND status IN ('CREATING','CREATION_UNKNOWN','PENDING') AND created_at>?",Integer.class,owner,boundaries.get(0));
            }else pending=db.queryForObject("SELECT COUNT(*) FROM payment_order WHERE creator_id=? AND status IN ('CREATING','CREATION_UNKNOWN','PENDING')",Integer.class,owner);
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
    private PaymentLegacyOrderBoundary legacyBoundary(){return new PaymentLegacyOrderBoundary(db,legacyOrdersReadOnly);}
    private void mutableOrder(String id){legacyBoundary().requireMutable(db.queryForMap("SELECT * FROM payment_order WHERE id=? AND provider=?",id,provider()));}
    /** Attach an existing provider order after an uncertain create; this never creates or captures an order. */
    public Map<String,Object> recover(String owner,String id,String remote){
        if(!ready())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        var original=row(owner,id);
        legacyBoundary().requireMutable(original);
        var snapshot=gateway().retrieve(remote);
        tx.executeWithoutResult(t->{
            db.queryForList("SELECT id FROM creator WHERE id=? FOR UPDATE",owner);
            var locked=db.queryForMap("SELECT * FROM payment_order WHERE id=? AND creator_id=? AND provider=? FOR UPDATE",id,owner,provider());
            legacyBoundary().requireMutable(locked);
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
        if(!legacyBoundary().readOnly(r) && ready() && !"PAID".equals(r.get("status")) && remote!=null){
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
    public Map<String,Object> orders(String owner){
        var activation=legacyBoundary().activation();
        return Map.of("orders",db.query("SELECT id,status,credits,created_at FROM payment_order WHERE creator_id=? AND provider=? ORDER BY created_at DESC,id DESC LIMIT 50",(rs,n)->{
            var view=new HashMap<String,Object>(Map.of("orderId",rs.getString(1),"status",rs.getString(2),"credits",rs.getLong(3),"createdAt",rs.getTimestamp(4).toInstant().toString()));
            if(activation!=null&&!rs.getTimestamp(4).after(activation))view.put("legacyReadOnly",true);
            return view;
        },owner,provider()));
    }
    public Map<String,Object> close(String owner,String id){
        var r=row(owner,id);
        legacyBoundary().requireMutable(r);
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
        legacyBoundary().requireMutable(r);
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
        var r=row(owner,id);legacyBoundary().requireMutable(r); if("PAID".equals(r.get("status"))) return order(owner,id);
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
            legacyBoundary().requireMutable(row);
            if(!"COMPLETED".equals(s.orderStatus()) || !"COMPLETED".equals(s.captureStatus()) || s.captureId()==null || s.captureId().isBlank()) throw new ResponseStatusException(HttpStatus.CONFLICT,"PayPal 付款尚未完成");
            Object old=row.get("provider_capture_id"); if(old!=null&&!old.equals(s.captureId())) throw new ResponseStatusException(HttpStatus.CONFLICT,"支付记录不匹配");
            ledger.credit((String)row.get("creator_id"),((Number)row.get("credits")).longValue(),"payment:"+id);
            db.update("UPDATE payment_order SET status='PAID',provider_capture_id=? WHERE id=?",s.captureId(),id);
            if(ledger instanceof com.qingfeng.music.infrastructure.persistence.credits.JdbcFencedCreditsLedger fenced){
                fenced.bindPayment("PAYPAL",merchantId,environment,owner,id,s.captureId(),s.currency());
                if(!db.queryForList("SELECT id FROM payment_risk_case WHERE order_id=? AND owner_id=? AND provider=? AND status IN ('OPEN','REVIEW') FOR UPDATE",id,owner,provider()).isEmpty())
                    fenced.holdPaymentForReview("PAYPAL",merchantId,environment,owner,id,s.captureId(),s.currency(),"paypal-unresolved-pre-settlement-risk");
            }
        });
    }
    /** Two independent authoritative GETs are required before recovering original receipt. */
    private PayPalCaptureRecoveryProof.Proven recoverOriginalCapture(String order,String captureId){
        var local=db.queryForMap("SELECT * FROM payment_order WHERE id=? AND provider=?",order,provider());
        legacyBoundary().requireMutable(local);
        var capture=gateway().retrieveCapture(captureId);
        if(!captureId.equals(capture.path("id").asString()))throw new IllegalStateException("Capture recovery identity mismatch");
        String remote=capture.path("supplementary_data").path("related_ids").path("order_id").asString();
        var snapshot=gateway().retrieve(remote);
        return PayPalCaptureRecoveryProof.verify(merchantId,environment,local,snapshot,capture);
    }
    /** Keep the ordinary settle contract strict; this path additionally requires original receipt proof. */
    private void settleRecoveredCapture(PayPalCaptureRecoveryProof.Proven proof){
        if(!(ledger instanceof com.qingfeng.music.infrastructure.persistence.credits.JdbcFencedCreditsLedger fenced))
            throw new IllegalStateException("Original capture recovery requires the fenced ledger");
        tx.executeWithoutResult(t->{
            if(db.queryForList("SELECT id FROM creator WHERE id=? FOR UPDATE",proof.owner()).size()!=1)throw new IllegalStateException("Capture recovery owner missing");
            var local=db.queryForMap("SELECT * FROM payment_order WHERE id=? AND creator_id=? AND provider=? FOR UPDATE",proof.order(),proof.owner(),provider());
            legacyBoundary().requireMutable(local);
            proof.assertCurrent(local,merchantId,environment);
            if(!"PAID".equals(local.get("status"))){
                ledger.credit(proof.owner(),proof.credits(),"payment:"+proof.order());
                db.update("UPDATE payment_order SET status='PAID',provider_link_id=?,provider_capture_id=?,failure_code=NULL WHERE id=? AND creator_id=? AND provider=?",proof.remoteOrder(),proof.capture(),proof.order(),proof.owner(),provider());
            }
            fenced.bindPayment("PAYPAL",merchantId,environment,proof.owner(),proof.order(),proof.capture(),proof.currency());
            // This evidence proves the original receipt only. Unknown refund/reversal
            // history continues to protect this order, without freezing other batches.
            fenced.holdPaymentForReview("PAYPAL",merchantId,environment,proof.owner(),proof.order(),proof.capture(),proof.currency(),proof.evidence());
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
        String pointer=new tools.jackson.databind.ObjectMapper().writeValueAsString(normalized);
        if(ledger instanceof com.qingfeng.music.infrastructure.persistence.credits.JdbcFencedCreditsLedger)
            inbox.acceptVerified(provider(),merchantId,environment,eventId,type,pointer);
        else inbox.acceptVerified(provider(),eventId,type,pointer);
    }
    public boolean processNextEvent(){
        if(!ready()||!inboxEnabled||inbox==null)return false;
        boolean fencedMode=ledger instanceof com.qingfeng.music.infrastructure.persistence.credits.JdbcFencedCreditsLedger;
        var found=fencedMode?inbox.claim(provider(),merchantId,environment):inbox.claim(provider());if(found.isEmpty())return false;
        var event=found.get();
        try{
            var payload=new tools.jackson.databind.ObjectMapper().readTree(event.payload());
            String resource=payload.path("resourceId").asString();
            if(legacyOrdersReadOnly){
                // Validate activation even when correlation is absent; never resolve unknown old money by guessing.
                legacyBoundary().activation();
                var known=db.queryForList("SELECT * FROM payment_order WHERE provider=? AND (provider_link_id=? OR provider_capture_id=?)",provider(),payload.path("orderId").asString(),resource);
                for(var local:known)legacyBoundary().requireMutable(local);
            }
            if("PAYMENT.CAPTURE.COMPLETED".equals(event.type())){
                String remote=payload.path("orderId").asString();
                if(remote.isBlank())remote=gateway().retrieveCapture(resource).path("supplementary_data").path("related_ids").path("order_id").asString();
                var snapshot=gateway().retrieve(remote);
                if(!resource.equals(snapshot.captureId()))throw new IllegalStateException("Capture correlation mismatch");
                String order=snapshot.reference();
                var local=db.queryForMap("SELECT * FROM payment_order WHERE id=? AND provider=?",order,provider());
                legacyBoundary().requireMutable(local);
                var expected=new HashMap<>(local);expected.put("provider_link_id",snapshot.orderId());check(expected,snapshot);
                if(local.get("provider_link_id")!=null&&!snapshot.orderId().equals(local.get("provider_link_id")))throw new IllegalStateException("Provider order mismatch");
                if(fencedMode&&Set.of("REFUNDED","PARTIALLY_REFUNDED","REVERSED").contains(snapshot.captureStatus())){
                    String owner=(String)local.get("creator_id");
                    PayPalCaptureRecoveryProof.Proven original=null;
                    if(!"PAID".equals(local.get("status"))&&!"REVERSED".equals(snapshot.captureStatus()))
                        original=recoverOriginalCapture(order,snapshot.captureId());
                    var proven=original;
                    inbox.waitForProvider(event,owner,()->{
                        if(proven!=null)settleRecoveredCapture(proven);
                        risks.recordVerified(provider(),snapshot.captureId(),"PAYMENT.CAPTURE.REFUND_DETECTED",order,event.eventId());
                        var current=db.queryForMap("SELECT status,currency FROM payment_order WHERE id=? AND creator_id=? AND provider=? FOR UPDATE",order,owner,provider());
                        if("PAID".equals(current.get("status")))((com.qingfeng.music.infrastructure.persistence.credits.JdbcFencedCreditsLedger)ledger)
                            .holdPaymentForReview("PAYPAL",merchantId,environment,owner,order,snapshot.captureId(),(String)current.get("currency"),event.eventId());
                    },"paypal-capture-risk-needs-funds-evidence");
                    return true;
                }
                inbox.completeOwned(event,(String)local.get("creator_id"),()->{
                    db.queryForList("SELECT id FROM creator WHERE id=? FOR UPDATE",local.get("creator_id"));
                    db.update("UPDATE payment_order SET provider_link_id=?,failure_code=NULL WHERE id=? AND provider=? AND provider_link_id IS NULL",snapshot.orderId(),order,provider());
                    if(Set.of("REFUNDED","PARTIALLY_REFUNDED","REVERSED").contains(snapshot.captureStatus())){
                        db.update("UPDATE payment_order SET provider_capture_id=? WHERE id=? AND (provider_capture_id IS NULL OR provider_capture_id=?)",snapshot.captureId(),order,snapshot.captureId());
                        risks.recordVerified(provider(),snapshot.captureId(),"PAYMENT.CAPTURE.REFUND_DETECTED",order,event.eventId());
                    }else settle(order,snapshot);
                });
            }else if("PAYMENT.CAPTURE.REFUNDED".equals(event.type())||"PAYMENT.CAPTURE.REVERSED".equals(event.type())||"PAYMENT.CAPTURE.STATUS_RISK".equals(event.type())||event.type().startsWith("CUSTOMER.DISPUTE.")){
                var captures=new LinkedHashSet<String>();
                PayPalEntitlementAdapter.Fact financial=null;
                if(event.type().startsWith("CUSTOMER.DISPUTE.")){
                    var dispute=gateway().retrieveDispute(resource);
                    if(!resource.equals(dispute.path("dispute_id").asString()))throw new IllegalStateException("Dispute identity mismatch");
                    if(fencedMode)financial=PayPalEntitlementAdapter.dispute(dispute);
                    for(var transaction:dispute.path("disputed_transactions")){String capture=transaction.path("seller_transaction_id").asString();if(!capture.isBlank())captures.add(capture);}
                }else if("PAYMENT.CAPTURE.REVERSED".equals(event.type())||"PAYMENT.CAPTURE.STATUS_RISK".equals(event.type())){
                    var capture=gateway().retrieveCapture(resource);if(!resource.equals(capture.path("id").asString()))throw new IllegalStateException("Capture identity mismatch");
                    if("PAYMENT.CAPTURE.STATUS_RISK".equals(event.type())&&!Set.of("REFUNDED","PARTIALLY_REFUNDED","REVERSED").contains(capture.path("status").asString())
                            &&(!fencedMode||!hasReviewForCapture(resource))){
                        inbox.complete(event,()->{});return true;
                    }
                    captures.add(resource);
                }else{
                    var refund=gateway().retrieveRefund(resource);if(!resource.equals(refund.path("id").asString()))throw new IllegalStateException("Refund identity mismatch");
                    if(fencedMode){financial=PayPalEntitlementAdapter.refund(refund,environment);captures.add(financial.capture());}
                    for(var link:refund.path("links"))if("up".equals(link.path("rel").asString())){
                        var uri=java.net.URI.create(link.path("href").asString());String prefix="/v2/payments/captures/";
                        if("https".equals(uri.getScheme())&&Set.of("api-m.paypal.com","api-m.sandbox.paypal.com","api.paypal.com","api.sandbox.paypal.com").contains(uri.getHost())&&uri.getPath().startsWith(prefix))captures.add(uri.getPath().substring(prefix.length()));
                    }
                }
                if(captures.isEmpty()||captures.size()>20)throw new IllegalStateException("Risk resource has no bounded payment correlation");
                if(fencedMode&&captures.size()!=1)throw new IllegalStateException("One financial identity cannot span multiple original payments");
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
                for(String order:orders){mutableOrder(order);owners.add(db.queryForObject("SELECT creator_id FROM payment_order WHERE id=? AND provider=?",String.class,order,provider()));}
                if(fencedMode&&(orders.size()!=1||owners.size()!=1))throw new IllegalStateException("Financial identity must bind one owner and order");
                var fact=financial;
                // Financial GETs run before completeOwned's owner transaction. A denied reporting
                // permission remains durable waiting, never a fabricated loss or a cleared hold.
                if(fencedMode&&orders.size()==1&&"PAID".equals(db.queryForObject("SELECT status FROM payment_order WHERE id=? AND provider=?",String.class,orders.first(),provider()))){
                    String id=orders.first(),owner=owners.first();
                    boolean needsProof=fact==null||event.type().startsWith("CUSTOMER.DISPUTE.")&&("LOST".equals(fact.state())||"WON".equals(fact.state())&&(isReview(id)||hasPayPalLoss(id,resource)))
                        ||"COMPLETED".equals(fact==null?"":fact.state())&&isReview(id);
                    if(needsProof){
                        try{
                            var prepared=prepareFunds(id,owner,captures.iterator().next(),event.type().startsWith("CUSTOMER.DISPUTE.")?resource:null);
                            inbox.completeOwned(event,owner,prepared);return true;
                        }catch(PayPalReportingGateway.Unavailable unavailable){
                            inbox.waitForProvider(event,owner,()->{
                                risks.recordVerified(provider(),resource,event.type(),id,event.eventId());
                                if(fact!=null&&event.type().startsWith("CUSTOMER.DISPUTE.")&&"LOST".equals(fact.state())&&!hasPayPalLoss(id,resource))
                                    PayPalEntitlementAdapter.apply((com.qingfeng.music.infrastructure.persistence.credits.JdbcFencedCreditsLedger)ledger,merchantId,environment,owner,id,fact,true);
                                else if(fact==null||isReview(id))((com.qingfeng.music.infrastructure.persistence.credits.JdbcFencedCreditsLedger)ledger)
                                    .holdPaymentForReview("PAYPAL",merchantId,environment,owner,id,captures.iterator().next(),(String)db.queryForMap("SELECT currency FROM payment_order WHERE id=?",id).get("currency"),event.eventId());
                            },"paypal-funds-"+unavailable.code().toLowerCase(Locale.ROOT));return true;
                        }
                    }
                }
                if(fencedMode&&fact==null){
                    String order=orders.first(),owner=owners.first(),capture=captures.iterator().next();
                    inbox.waitForProvider(event,owner,()->{
                        risks.recordVerified(provider(),resource,event.type(),order,event.eventId());
                        var local=db.queryForMap("SELECT status,currency FROM payment_order WHERE id=? AND creator_id=? AND provider=? FOR UPDATE",order,owner,provider());
                        if("PAID".equals(local.get("status")))((com.qingfeng.music.infrastructure.persistence.credits.JdbcFencedCreditsLedger)ledger)
                            .holdPaymentForReview("PAYPAL",merchantId,environment,owner,order,capture,(String)local.get("currency"),event.eventId());
                    },"paypal-capture-risk-needs-funds-evidence");
                    return true;
                }
                if(fencedMode&&fact!=null){
                    String order=orders.first(),owner=owners.first();
                    if(!"PAID".equals(db.queryForObject("SELECT status FROM payment_order WHERE id=? AND creator_id=? AND provider=?",String.class,order,owner,provider()))){
                        // The refund itself is never payment evidence. Independently bind
                        // the original capture/order/payee/principal using authoritative GETs.
                        var original=recoverOriginalCapture(order,fact.capture());
                        inbox.waitForProvider(event,owner,()->{
                            settleRecoveredCapture(original);
                            risks.recordVerified(provider(),resource,event.type(),order,event.eventId());
                            var current=db.queryForMap("SELECT status FROM payment_order WHERE id=? AND creator_id=? AND provider=? FOR UPDATE",order,owner,provider());
                            if("PAID".equals(current.get("status"))&&(event.type().startsWith("CUSTOMER.DISPUTE.")||"COMPLETED".equals(fact.state())))
                                PayPalEntitlementAdapter.apply((com.qingfeng.music.infrastructure.persistence.credits.JdbcFencedCreditsLedger)ledger,merchantId,environment,owner,order,fact,event.type().startsWith("CUSTOMER.DISPUTE."));
                        },"paypal-original-capture-recovered-awaiting-complete-financial-evidence");
                        return true;
                    }
                    if(!event.type().startsWith("CUSTOMER.DISPUTE.")&&!"COMPLETED".equals(fact.state())){
                        inbox.waitForProvider(event,owner,()->risks.recordVerified(provider(),resource,event.type(),order,event.eventId()),"paypal-refund-not-successful");
                        return true;
                    }
                    if(event.type().startsWith("CUSTOMER.DISPUTE.")&&Set.of("UNKNOWN","LOST").contains(fact.state())){
                        inbox.waitForProvider(event,owner,()->{
                            risks.recordVerified(provider(),resource,event.type(),order,event.eventId());
                            PayPalEntitlementAdapter.apply((com.qingfeng.music.infrastructure.persistence.credits.JdbcFencedCreditsLedger)ledger,merchantId,environment,owner,order,fact,true);
                        },"paypal-dispute-needs-final-principal-evidence");
                        return true;
                    }
                }
                inbox.completeOwned(event,owners,()->{
                    for(String owner:owners)db.queryForList("SELECT id FROM creator WHERE id=? FOR UPDATE",owner);
                    for(var entry:recovered.entrySet()){
                        var snapshot=entry.getValue();
                        db.update("UPDATE payment_order SET provider_link_id=?,provider_capture_id=? WHERE id=? AND provider=? AND (provider_link_id IS NULL OR provider_link_id=?) AND (provider_capture_id IS NULL OR provider_capture_id=?)",snapshot.orderId(),snapshot.captureId(),entry.getKey(),provider(),snapshot.orderId(),snapshot.captureId());
                    }
                    for(String order:orders)risks.recordVerified(provider(),resource,event.type(),order,event.eventId());
                    if(fencedMode&&fact!=null)PayPalEntitlementAdapter.apply((com.qingfeng.music.infrastructure.persistence.credits.JdbcFencedCreditsLedger)ledger,merchantId,environment,owners.first(),orders.first(),fact,event.type().startsWith("CUSTOMER.DISPUTE."));
                });
            }else if(legacyOrdersReadOnly)inbox.markManual(event,"LEGACY_CHANNEL_EVENT_CORRELATION_REQUIRED");
            else inbox.complete(event,()->{});
        }catch(PaymentLegacyOrderBoundary.ReadOnlyOrder historical){inbox.markManual(event,"LEGACY_ORDER_READ_ONLY");}
        catch(RuntimeException failed){
            if(legacyOrdersReadOnly)inbox.markManual(event,"LEGACY_CHANNEL_UNRESOLVED");else inbox.retry(event);
        }
        return true;
    }
    private boolean isReview(String id){
        return !db.queryForList("SELECT reference FROM p0_credit_batch WHERE reference=? AND status='REVIEW'","payment:"+id).isEmpty();
    }
    private boolean hasPayPalLoss(String order,String dispute){
        return !db.queryForList("SELECT dispute_id FROM p0_stripe_dispute_proof WHERE provider='PAYPAL' AND merchant=? AND environment=? AND order_id=? AND dispute_id=?",merchantId,environment,order,dispute).isEmpty();
    }
    private boolean hasReviewForCapture(String capture){
        return !db.queryForList("SELECT o.id FROM payment_order o JOIN p0_credit_batch b ON b.reference=CONCAT('payment:',o.id) WHERE o.provider=? AND o.provider_capture_id=? AND b.status='REVIEW'",provider(),capture).isEmpty();
    }
    private record ReviewCase(String event,String status,String decision,String reviewer){}
    private record ReviewSnapshot(Map<String,String> events,Map<String,ReviewCase> cases){}
    private ReviewSnapshot riskSnapshot(String owner,String id){
        var rows=db.queryForList("SELECT id,last_event_id,status,decision,reviewed_by FROM payment_risk_case WHERE owner_id=? AND order_id=? AND provider=? ORDER BY id",owner,id,provider());
        if(rows.size()>100)throw new PayPalReportingGateway.Unavailable("REVIEW_CASE_LIMIT");
        var events=new TreeMap<String,String>();var cases=new TreeMap<String,ReviewCase>();
        for(var row:rows){
            if("HOLD".equals(row.get("decision")))throw new PayPalReportingGateway.Unavailable("MANUAL_HOLD_REQUIRES_REVIEW");
            if(!Set.of("OPEN","REVIEW","CLOSED").contains(row.get("status")))throw new PayPalReportingGateway.Unavailable("REVIEW_CASE_STATUS_UNSUPPORTED");
            String key=(String)row.get("id"),event=(String)row.get("last_event_id");events.put(key,event);
            cases.put(key,new ReviewCase(event,(String)row.get("status"),(String)row.get("decision"),(String)row.get("reviewed_by")));
        }
        return new ReviewSnapshot(Map.copyOf(events),Map.copyOf(cases));
    }
    private void requireReviewUnchanged(ReviewSnapshot expected,String owner,String id){
        if(!expected.equals(riskSnapshot(owner,id)))throw new PayPalReportingGateway.Unavailable("REVIEW_CASE_CHANGED");
    }
    /** Immutable order/risk snapshots are taken before provider reads; the ledger checks them again under owner lock. */
    private Runnable prepareFunds(String id,String owner,String captureId,String disputeId){
        try{
            var row=db.queryForMap("SELECT * FROM payment_order WHERE id=? AND creator_id=? AND provider=?",id,owner,provider());
            var review=riskSnapshot(owner,id);
            var scope=new PayPalFundsProof.Scope(merchantId,environment);
            var original=new PayPalFundsProof.Order(id,captureId,(String)row.get("provider_link_id"),(String)row.get("currency"),((BigDecimal)row.get("amount")).movePointRight(2).longValueExact());
            var capture=gateway().retrieveCapture(captureId);
            var start=java.time.Instant.parse(capture.path("create_time").asString()).minusSeconds(60);
            // PayPal documents up to three hours of Reporting delay. Never label a
            // present-time window complete while its settlement watermark lags it.
            var report=gateway().financialReport(start,java.time.Instant.now().minusSeconds(3*3600+300));
            var disputes=gateway().disputesForCapture(captureId);
            var fenced=(com.qingfeng.music.infrastructure.persistence.credits.JdbcFencedCreditsLedger)ledger;
            if(disputeId!=null){
                var dispute=gateway().retrieveDispute(disputeId);
                var evidence=new PayPalFundsProof.Evidence(scope,capture,dispute,disputes,report);
                var fact=PayPalEntitlementAdapter.dispute(dispute);
                if(!disputeId.equals(fact.id())||!captureId.equals(fact.capture()))throw new IllegalStateException("Financial dispute correlation mismatch");
                if("LOST".equals(fact.state())){
                    var proof=PayPalFundsProof.loss(scope,original,evidence);
                    if(!sameFinancialDispute(dispute,gateway().retrieveDispute(disputeId))||!capture.equals(gateway().retrieveCapture(captureId)))throw new IllegalStateException("Payment changed during reporting");
                    return ()->{
                        requireReviewUnchanged(review,owner,id);
                        persistPayPalPrincipal(row,disputeId,proof);
                        fenced.settlePayPalReportedLoss(merchantId,environment,disputeId,owner,id,captureId,fact.amount(),fact.currency(),fact.version(),proof.lossMinor(),proof.evidenceSha256());
                        resolveReportedReview(fenced,review,owner,id,captureId,original.currency(),0,proof.lossMinor(),proof.evidenceSha256());
                    };
                }
                if("WON".equals(fact.state())){
                    var prior=db.queryForList("SELECT * FROM p0_stripe_dispute_proof WHERE provider='PAYPAL' AND merchant=? AND environment=? AND dispute_id=?",merchantId,environment,disputeId);
                    if(prior.isEmpty()){
                        var proof=PayPalFundsProof.review(scope,original,evidence);
                        var refunds=reportedRefunds(proof.refunds(),captureId,original.currency());
                        if(!sameFinancialDispute(dispute,gateway().retrieveDispute(disputeId))||!capture.equals(gateway().retrieveCapture(captureId)))
                            throw new IllegalStateException("Payment changed during reporting");
                        return ()->{
                            requireReviewUnchanged(review,owner,id);
                            fenced.paymentDispute("PAYPAL",merchantId,environment,disputeId,owner,id,captureId,fact.amount(),fact.currency(),fact.version(),"WON",null);
                            for(var refund:refunds)PayPalEntitlementAdapter.apply(fenced,merchantId,environment,owner,id,refund,false);
                            resolveReportedReview(fenced,review,owner,id,captureId,original.currency(),proof.refundedMinor(),0,proof.evidenceSha256());
                        };
                    }
                    if(prior.size()!=1)throw new PayPalReportingGateway.Unavailable("RETURN_ORIGINAL_LOSS_PROOF_REQUIRED");
                    var saved=principal(prior.get(0),original,owner);
                    var proof=PayPalFundsProof.returned(scope,original,evidence,saved);
                    if(!sameFinancialDispute(dispute,gateway().retrieveDispute(disputeId))||!capture.equals(gateway().retrieveCapture(captureId)))throw new IllegalStateException("Payment changed during reporting");
                    return ()->{
                        requireReviewUnchanged(review,owner,id);
                        if(!saved.equals(principal(db.queryForMap("SELECT * FROM p0_stripe_dispute_proof WHERE provider='PAYPAL' AND merchant=? AND environment=? AND dispute_id=? FOR UPDATE",merchantId,environment,disputeId),original,owner)))throw new IllegalStateException("PayPal recorded principal changed");
                        fenced.compensateReturnedFunds("PAYPAL",merchantId,environment,proof.transactionId(),disputeId,owner,id,captureId,proof.returnedMinor(),original.currency(),proof.evidenceSha256());
                        resolveReportedReview(fenced,review,owner,id,captureId,original.currency(),0,0,proof.evidenceSha256());
                    };
                }
                throw new PayPalReportingGateway.Unavailable("FINAL_DISPUTE_EVIDENCE_REQUIRED");
            }
            var proof=PayPalFundsProof.review(scope,original,capture,disputes,report);
            var refunds=reportedRefunds(proof.refunds(),captureId,original.currency());
            var after=gateway().retrieveCapture(captureId);
            if(!capture.equals(after)||!disputes.equals(gateway().disputesForCapture(captureId)))throw new IllegalStateException("Payment changed during reporting");
            return ()->{
                requireReviewUnchanged(review,owner,id);
                for(var refund:refunds)PayPalEntitlementAdapter.apply(fenced,merchantId,environment,owner,id,refund,false);
                resolveReportedReview(fenced,review,owner,id,captureId,original.currency(),proof.refundedMinor(),proof.lossMinor(),proof.evidenceSha256());
            };
        }catch(PayPalReportingGateway.Unavailable unavailable){throw unavailable;}
        catch(RuntimeException ambiguous){throw new PayPalReportingGateway.Unavailable("FINANCIAL_PROOF_INCOMPLETE");}
    }
    private List<PayPalEntitlementAdapter.Fact> reportedRefunds(List<PayPalFundsProof.Refund> proof,String capture,String currency){
        var refunds=new ArrayList<PayPalEntitlementAdapter.Fact>();
        for(var refund:proof){
            var verified=PayPalEntitlementAdapter.refund(gateway().retrieveRefund(refund.id()),environment);
            if(!refund.id().equals(verified.id())||!capture.equals(verified.capture())||refund.amountMinor()!=verified.amount()||!currency.equals(verified.currency())||!"COMPLETED".equals(verified.state()))
                throw new IllegalStateException("Reported refund does not match authoritative resource");
            refunds.add(verified);
        }
        return List.copyOf(refunds);
    }
    private boolean sameFinancialDispute(tools.jackson.databind.JsonNode before,tools.jackson.databind.JsonNode after){
        for(String field:List.of("dispute_id","status","update_time","dispute_amount","dispute_outcome","fund_movements","disputed_transactions"))
            if(!before.path(field).equals(after.path(field)))return false;
        return true;
    }
    private PayPalFundsProof.Loss principal(Map<String,Object> saved,PayPalFundsProof.Order original,String owner){
        if(!owner.equals(saved.get("owner_id"))||!original.orderId().equals(saved.get("order_id"))||!original.captureId().equals(saved.get("capture_id"))
            ||!original.currency().equals(saved.get("settlement_currency"))||original.paidMinor()!=((Number)saved.get("settlement_principal_minor")).longValue())
            throw new IllegalStateException("PayPal recorded principal scope mismatch");
        return new PayPalFundsProof.Loss(((Number)saved.get("loss_minor")).longValue(),(String)saved.get("withdrawal_id"),(String)saved.get("charge_balance_id"),(String)saved.get("evidence_sha256"));
    }
    private void persistPayPalPrincipal(Map<String,Object> row,String dispute,PayPalFundsProof.Loss proof){
        if(db.queryForObject("SELECT COUNT(*) FROM p0_credit_refund WHERE order_id=?",Long.class,row.get("id"))!=0
            ||db.queryForObject("SELECT COUNT(*) FROM p0_credit_dispute WHERE order_id=? AND external_id<>?",Long.class,row.get("id"),dispute)!=0)
            throw new IllegalStateException("PAYPAL_OVERLAP_REQUIRES_VERIFIED_RESOLUTION");
        try{db.update("INSERT INTO p0_stripe_dispute_proof(provider,merchant,environment,dispute_id,order_id,capture_id,owner_id,loss_minor,withdrawal_id,charge_balance_id,settlement_currency,settlement_principal_minor,evidence_sha256,created_at) VALUES('PAYPAL',?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",merchantId,environment,dispute,row.get("id"),row.get("provider_capture_id"),row.get("creator_id"),proof.lossMinor(),proof.withdrawalId(),proof.originalTransactionId(),row.get("currency"),((BigDecimal)row.get("amount")).movePointRight(2).longValueExact(),proof.evidenceSha256());}
        catch(org.springframework.dao.DuplicateKeyException replay){
            var old=db.queryForMap("SELECT * FROM p0_stripe_dispute_proof WHERE provider='PAYPAL' AND merchant=? AND environment=? AND dispute_id=? FOR UPDATE",merchantId,environment,dispute);
            var bound=principal(old,new PayPalFundsProof.Order((String)row.get("id"),(String)row.get("provider_capture_id"),(String)row.get("provider_link_id"),(String)row.get("currency"),((BigDecimal)row.get("amount")).movePointRight(2).longValueExact()),(String)row.get("creator_id"));
            if(!proof.equals(bound))throw new IllegalStateException("PayPal native principal identity conflict");
        }
    }
    private void resolveReportedReview(com.qingfeng.music.infrastructure.persistence.credits.JdbcFencedCreditsLedger fenced,ReviewSnapshot expected,String owner,String id,String capture,String currency,long refunds,long losses,String proof){
        requireReviewUnchanged(expected,owner,id);
        for(var entry:expected.events().entrySet())risks.resolveCase(entry.getKey(),entry.getValue(),"RELEASE","paypal-authoritative-reporting","Verified PayPal native funds proof sha256:"+proof);
        if(isReview(id))fenced.releasePaymentReview("PAYPAL",merchantId,environment,owner,id,capture,currency,refunds,losses,expected.events(),proof);
    }
    /** Bounded, read-only provider reconciliation for existing linked orders; never captures or recreates. */
    public void reconcileOrders(){
        if(!ready())return;
        var activation=legacyBoundary().activation();String boundary=activation==null?"":" AND created_at>?";
        Object[] arguments=activation==null?new Object[]{provider()}:new Object[]{provider(),activation};
        db.update("UPDATE payment_order SET status='CREATION_UNKNOWN',failure_code='CREATE_PROCESS_INTERRUPTED' WHERE provider=? AND status='CREATING' AND provider_link_id IS NULL AND created_at<TIMESTAMPADD(MINUTE,-5,CURRENT_TIMESTAMP)"+boundary,arguments);
        var rows=db.queryForList("SELECT id,creator_id FROM payment_order WHERE provider=? AND status IN ('PENDING','CANCELLED') AND provider_link_id IS NOT NULL AND (reconcile_after IS NULL OR reconcile_after<CURRENT_TIMESTAMP)"+boundary+" ORDER BY reconcile_after,created_at,id LIMIT 10",arguments);
        for(var row:rows){
            if(db.update("UPDATE payment_order SET reconcile_after=TIMESTAMPADD(MINUTE,5,CURRENT_TIMESTAMP) WHERE id=? AND (reconcile_after IS NULL OR reconcile_after<CURRENT_TIMESTAMP)",row.get("id"))!=1)continue;
            try{order((String)row.get("creator_id"),(String)row.get("id"));}catch(RuntimeException ignored){/* persisted due time permits a later retry */}
        }
        reconcilePaidCaptures();
    }
    /** Detect a missed refund callback for recent paid orders. Disputes still require their verified webhook. */
    private void reconcilePaidCaptures(){
        if(!inboxEnabled||inbox==null)return;
        var activation=legacyBoundary().activation();String boundary=activation==null?"":" AND created_at>?";
        Object[] arguments=activation==null?new Object[]{provider()}:new Object[]{provider(),activation};
        var rows=db.queryForList("SELECT id,provider_link_id,provider_capture_id,amount,currency FROM payment_order WHERE provider=? AND status='PAID' AND provider_capture_id IS NOT NULL AND created_at>=TIMESTAMPADD(DAY,-30,CURRENT_TIMESTAMP) AND (reconcile_after IS NULL OR reconcile_after<CURRENT_TIMESTAMP)"+boundary+" ORDER BY reconcile_after,created_at,id LIMIT 10",arguments);
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
                if(ledger instanceof com.qingfeng.music.infrastructure.persistence.credits.JdbcFencedCreditsLedger)
                    inbox.acceptVerified(provider(),merchantId,environment,eventId,"PAYMENT.CAPTURE.STATUS_RISK",payload);
                else inbox.acceptVerified(provider(),eventId,"PAYMENT.CAPTURE.STATUS_RISK",payload);
            }catch(RuntimeException ignored){/* persisted due time permits a later retry; no balance adjustment */}
        }
    }
}
