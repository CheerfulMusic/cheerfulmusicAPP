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
public class PayPalPaymentService {
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
    // Refund/dispute webhook events are not implemented yet; merchant acceptance must be completed
    // before public-live-enabled is switched on. See docs/PayPal收款接入.md.
    boolean ready(){return enabled && !clientId.isBlank() && !clientSecret.isBlank() && !merchantId.isBlank()
        && ((environment.equals("sandbox") && !webhookId.isBlank()) || localLive() || publicLive());}
    String provider(){return environment.equals("live")?"PAYPAL_LIVE":"PAYPAL_SANDBOX";}
    PayPalCheckoutGateway gateway(){return new PayPalCheckoutGateway(environment.equals("live")?PayPalCheckoutGateway.Environment.LIVE:PayPalCheckoutGateway.Environment.SANDBOX,clientId,clientSecret,merchantId,webhookId,localLive());}
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
            Integer pending=db.queryForObject("SELECT COUNT(*) FROM payment_order WHERE creator_id=? AND status IN ('CREATING','PENDING')",Integer.class,owner);
            if(pending!=null && pending>0) throw new ResponseStatusException(HttpStatus.CONFLICT,"已有待核对的订单");
            db.update("INSERT INTO payment_order(id,creator_id,package_id,amount,currency,credits,status,created_at,provider) VALUES(?,?,?,?,?,?,?,CURRENT_TIMESTAMP,?)",id,owner,p.id(),p.amount(),p.currency(),p.credits(),"CREATING",type);
        });
        var link=gateway().create(id,p.title(),p.amount(),p.currency(),returnBase+"?paypal_order="+id,returnBase+"?paypal_cancel="+id);
        db.update("UPDATE payment_order SET provider_link_id=?,status=CASE WHEN status='CREATING' THEN 'PENDING' ELSE status END WHERE id=? AND provider=? AND (provider_link_id IS NULL OR provider_link_id=?)",link.orderId(),id,type,link.orderId());
        return Map.of("orderId",id,"checkoutUrl",link.approvalUrl(),"paypalOrderId",link.orderId());
    }
    Map<String,Object> row(String owner,String id){
        var rows=db.queryForList("SELECT * FROM payment_order WHERE id=? AND creator_id=? AND provider=?",id,owner,provider());
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND); return rows.get(0);
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
        if(!ready() || webhookId.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        var gateway=gateway();
        if(!gateway.verifyWebhook(headers,body)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        var event=new tools.jackson.databind.ObjectMapper().readTree(body);
        if(!"PAYMENT.CAPTURE.COMPLETED".equals(event.path("event_type").asString()))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"事件处理尚未实现");
        var resource=event.path("resource");
        String remote=resource.path("supplementary_data").path("related_ids").path("order_id").asString();
        String capture=resource.path("id").asString();
        if(remote.isBlank()||capture.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var rows=db.queryForList("SELECT id FROM payment_order WHERE provider=? AND provider_link_id=?",provider(),remote);
        if(rows.size()!=1) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"订单待核对");
        var snapshot=gateway.retrieve(remote);
        if(!capture.equals(snapshot.captureId())) throw new ResponseStatusException(HttpStatus.CONFLICT);
        settle((String)rows.get(0).get("id"),snapshot);
    }
}
