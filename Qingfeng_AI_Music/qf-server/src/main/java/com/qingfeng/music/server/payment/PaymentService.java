package com.qingfeng.music.server.payment;

import com.qingfeng.music.credits.CreditsLedger;
import com.qingfeng.music.server.security.CreatorPrincipal;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Sandbox-only payment link integration. Production requires reconciliation and refund handling. */
@org.springframework.stereotype.Service
@Profile({"mysql","test"})
public class PaymentService {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final CreditsLedger ledger;
    private final PaymentGateway gateway;
    private final PaymentCatalog catalog;
    @Value("${qingfeng.payments.enabled:false}") private boolean enabled;

    public PaymentService(JdbcTemplate db, TransactionTemplate tx, CreditsLedger ledger, PaymentGateway gateway, PaymentCatalog catalog) {
        this.db=db; this.tx=tx; this.ledger=ledger; this.gateway=gateway; this.catalog=catalog;
    }
    private boolean ready() {
        return enabled && configured();
    }
    private boolean configured() { return gateway.configured(); }
    public Map<String,Object> catalog() {
        return Map.of("enabled",ready(),"packages",ready()?catalog.packages().stream().map(PaymentCatalog.Package::asMap).toList():List.of());
    }
    public record OrderRequest(String packageId) {}
    public Map<String,Object> create(CreatorPrincipal creator, OrderRequest req) {
        if(!ready()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"支付尚未开放");
        var selected=catalog.find(req.packageId()).orElseThrow(()->new ResponseStatusException(HttpStatus.BAD_REQUEST,"套餐不存在"));
        String id=UUID.randomUUID().toString();
        reserve(creator.id(),id,selected.id(),selected.amount(),selected.currency(),selected.credits());
        // The order exists before contacting the provider so a racing webhook can find it.
        PaymentGateway.Link link;
        try { link=gateway.create(id,selected.title(),selected.amount(),selected.currency()); }
        catch (PaymentGateway.CreationRejected rejected) {
            db.update("UPDATE payment_order SET status='FAILED' WHERE id=? AND status='CREATING' AND provider_link_id IS NULL",id);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"支付平台拒绝创建订单，可稍后重试");
        }
        // Transport failures remain unresolved: a remote order may already exist.
        String linkId=link.id(), url=link.url();
        db.update("UPDATE payment_order SET provider_link_id=?,status=CASE WHEN status='CREATING' THEN 'PENDING' ELSE status END WHERE id=? AND (provider_link_id IS NULL OR provider_link_id=?)",linkId,id,linkId);
        return Map.of("orderId",id,"checkoutUrl",url);
    }
    void reserve(String owner,String id,String selectedPackage,BigDecimal selectedAmount,String selectedCurrency,long selectedCredits) {
        tx.executeWithoutResult(s -> {
            // Lock the owner even when there are no orders yet; this serializes the empty-set check.
            var creators=db.queryForList("SELECT id FROM creator WHERE id=? FOR UPDATE",owner);
            if(creators.isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
            Integer identities=db.queryForObject("SELECT (SELECT COUNT(*) FROM creator_google_identity WHERE creator_id=?) + (SELECT COUNT(*) FROM creator_oidc_identity WHERE creator_id=?)",Integer.class,owner,owner);
            if(identities==null || identities==0) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"请先登录账号");
            Integer pending=db.queryForObject("SELECT COUNT(*) FROM payment_order WHERE creator_id=? AND status IN ('CREATING','PENDING')",Integer.class,owner);
            if(pending!=null && pending>0) throw new ResponseStatusException(HttpStatus.CONFLICT,"已有待核对的订单，请先查看订单状态");
            db.update("INSERT INTO payment_order(id,creator_id,package_id,amount,currency,credits,status,created_at) VALUES(?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",id,owner,selectedPackage,selectedAmount,selectedCurrency,selectedCredits,"CREATING");
        });
    }
    public Map<String,Object> order(CreatorPrincipal creator,String id) {
        var rows=db.queryForList("SELECT id,status,credits,provider_link_id FROM payment_order WHERE id=? AND creator_id=? AND provider='AIRWALLEX'",id,creator.id());
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var row=rows.get(0);
        if(configured() && !"PAID".equals(row.get("status")) && row.get("provider_link_id")!=null) {
            String linkId=(String)row.get("provider_link_id");
            PaymentGateway.Snapshot remote=gateway.retrieve(linkId);
            if("PAID".equals(remote.status())) {settle(id,linkId,remote); row=db.queryForMap("SELECT id,status,credits FROM payment_order WHERE id=?",id);}
        }
        return Map.of("orderId",row.get("id"),"status",row.get("status"),"credits",row.get("credits"));
    }
    /** Attach the original support-confirmed link after an unknown create; never create a replacement. */
    public Map<String,Object> recover(CreatorPrincipal creator,String id,String linkId) {
        if(!ready()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        if(linkId==null || !linkId.matches("[A-Za-z0-9_-]{1,128}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        if(db.queryForList("SELECT id FROM payment_order WHERE id=? AND creator_id=? AND provider='AIRWALLEX'",id,creator.id()).isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var remote=gateway.retrieve(linkId);
        return tx.execute(t->{
            var row=db.queryForMap("SELECT * FROM payment_order WHERE id=? AND creator_id=? AND provider='AIRWALLEX' FOR UPDATE",id,creator.id());
            if(!id.equals(remote.reference()) || !linkId.equals(remote.id()) || remote.reusable()
                || !row.get("currency").equals(remote.currency()) || remote.amount()==null
                || ((BigDecimal)row.get("amount")).compareTo(remote.amount())!=0
                || (row.get("provider_link_id")!=null&&!linkId.equals(row.get("provider_link_id"))))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"支付信息不匹配");
            if(!List.of("CREATING","PENDING","PAID").contains(row.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT);
            db.update("UPDATE payment_order SET provider_link_id=?,status=CASE WHEN status='CREATING' THEN 'PENDING' ELSE status END WHERE id=?",linkId,id);
            if("PAID".equals(remote.status())) settle(id,linkId,remote);
            return Map.of("orderId",id,"status",db.queryForObject("SELECT status FROM payment_order WHERE id=?",String.class,id),"credits",row.get("credits"));
        });
    }
    public Map<String,Object> orders(CreatorPrincipal creator) {
        var orders=db.query("SELECT id,status,credits,created_at FROM payment_order WHERE creator_id=? AND provider='AIRWALLEX' ORDER BY created_at DESC,id DESC LIMIT 50",
            (rs,n)->Map.of("orderId",rs.getString("id"),"status",rs.getString("status"),"credits",rs.getLong("credits"),"createdAt",rs.getTimestamp("created_at").toInstant().toString()),creator.id());
        return Map.of("orders",orders);
    }
    public void webhook(String timestamp,String signature,byte[] body) {
        PaymentGateway.Notification notification=gateway.notification(timestamp,signature,body);
        if(notification==null) return;
        settle(notification.reference(),notification.linkId(),gateway.retrieve(notification.linkId()));
    }
    void settle(String id,String linkId,PaymentGateway.Snapshot remote) {
        tx.executeWithoutResult(s -> {
            var rows=db.queryForList("SELECT * FROM payment_order WHERE id=? AND provider='AIRWALLEX' FOR UPDATE",id);
            if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"订单尚未就绪");
            var row=rows.get(0);
            String prior=(String)row.get("provider_link_id");
            if((prior!=null && !prior.equals(linkId)) || !id.equals(remote.reference()) || !linkId.equals(remote.id())
                || !"PAID".equals(remote.status()) || remote.reusable()
                || !row.get("currency").equals(remote.currency()) || remote.amount()==null
                || ((BigDecimal)row.get("amount")).compareTo(remote.amount())!=0
                || remote.successfulPaymentId().isBlank()) throw new ResponseStatusException(HttpStatus.CONFLICT,"支付信息不匹配");
            ledger.credit((String)row.get("creator_id"),((Number)row.get("credits")).longValue(),"payment:"+id);
            db.update("UPDATE payment_order SET status='PAID',provider_link_id=? WHERE id=?",linkId,id);
        });
    }
}
