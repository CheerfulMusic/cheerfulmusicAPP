package com.qingfeng.music.server.payment;

import com.qingfeng.music.credits.CreditsLedger;
import com.qingfeng.music.infrastructure.persistence.credits.JdbcFencedCreditsLedger;
import com.qingfeng.music.server.security.CreatorPrincipal;
import com.qingfeng.music.server.config.CreditSchemaReadiness;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Hosted card/Apple Pay and WeChat checkout using immutable server-owned offers. */
@Service
@Profile({"mysql", "test"})
public final class StripePaymentService implements VerifiedPaymentProcessor {
    private static final Set<String> EVENTS = Set.of(
        "checkout.session.completed", "checkout.session.async_payment_succeeded",
        "checkout.session.async_payment_failed", "checkout.session.expired",
        "refund.created", "refund.updated", "refund.failed", "charge.dispute.created",
        "charge.dispute.updated", "charge.dispute.closed", "charge.dispute.funds_reinstated");
    private static final String ORDER_RECONCILE="stripe.order.reconcile";
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final CreditsLedger ledger;
    private final PaymentCatalog catalog;
    private final StripePaymentMethods paymentMethods;
    private final PaymentAcceptanceOffers acceptanceOffers;
    private final StripeCheckoutGateway gateway;
    private final ObjectMapper json;
    private final PaymentInbox inbox;
    private java.time.Clock reconciliationClock=java.time.Clock.systemUTC();
    @Value("${qingfeng.stripe.enabled:false}") private boolean enabled;
    @Value("${qingfeng.stripe.accepting-new-orders:false}") private boolean acceptingNewOrders;
    @Value("${qingfeng.stripe.canary-owner-ids:}") private String canaryOwnerIds="";
    @Value("${qingfeng.payment.legacy-orders-read-only:false}") private boolean legacyOrdersReadOnly;

    public StripePaymentService(JdbcTemplate db, TransactionTemplate tx, CreditsLedger ledger,
                                PaymentCatalog catalog, StripeCheckoutGateway gateway, ObjectMapper json) {
        this(db,tx,ledger,catalog,gateway,json,new PaymentInbox(db,tx));
    }
    public StripePaymentService(JdbcTemplate db, TransactionTemplate tx, CreditsLedger ledger,
                                PaymentCatalog catalog, StripeCheckoutGateway gateway, ObjectMapper json, PaymentInbox inbox) {
        this(db,tx,ledger,catalog,gateway,json,inbox,new org.springframework.core.env.StandardEnvironment());
    }
    @org.springframework.beans.factory.annotation.Autowired
    public StripePaymentService(JdbcTemplate db, TransactionTemplate tx, CreditsLedger ledger,
                                PaymentCatalog catalog, StripeCheckoutGateway gateway, ObjectMapper json, PaymentInbox inbox,
                                org.springframework.core.env.Environment environment) {
        this.db=db; this.tx=tx; this.ledger=ledger; this.catalog=catalog; this.gateway=gateway; this.json=json; this.inbox=inbox;
        this.paymentMethods=new StripePaymentMethods(catalog,environment);
        this.acceptanceOffers=new PaymentAcceptanceOffers(environment);
    }
    public record Resolution(boolean verified,String evidenceId,Runnable apply,String ownerId,boolean pending){
        public Resolution(boolean verified,String evidenceId,Runnable apply,String ownerId){this(verified,evidenceId,apply,ownerId,false);}
        public Resolution(boolean verified,String evidenceId,Runnable apply){this(verified,evidenceId,apply,null,false);}
        public static Resolution pending(String evidenceId,Runnable apply,String ownerId){return new Resolution(true,evidenceId,apply,ownerId,true);}
    }
    @Override public String provider(){return gateway.provider();}

    private boolean schemaReady() {
        if (!(ledger instanceof JdbcFencedCreditsLedger) || !enabled || !gateway.configured()) return false;
        try {
            if(!CreditSchemaReadiness.ready(db))return false;
            Integer otherModePending=db.queryForObject("SELECT COUNT(*) FROM payment_order WHERE provider IN ('STRIPE_SANDBOX','STRIPE_LIVE') AND provider<>? AND status IN ('CREATING','CREATION_UNKNOWN','PENDING')",Integer.class,gateway.provider());
            return otherModePending!=null && otherModePending==0;
        } catch (RuntimeException missingMigration) { return false; }
    }

    public Map<String,Object> catalog() {
        return catalog(null);
    }

    public Map<String,Object> catalog(CreatorPrincipal creator) {
        boolean open=schemaReady() && purchaseAllowed(creator);
        var methods=paymentMethods.catalog(open);
        boolean privateAcceptance=!acceptingNewOrders&&creator!=null&&acceptanceOffers.configuredFor(creator.id());
        if(open&&privateAcceptance){
            methods=methods.stream().map(method->{
                String channel=(String)method.get("id");
                var result=new LinkedHashMap<String,Object>(method);
                result.put("packages",List.of());
                acceptanceOffers.offer(creator.id(),channel).filter(pack->Boolean.TRUE.equals(method.get("enabled"))
                    && acceptanceOffers.unused(db,creator.id(),pack.id(),provider())).ifPresent(pack->{
                    result.put("packages",List.of(acceptanceOffers.view(pack,channel)));
                });return Map.copyOf(result);
            }).toList();
        }
        return Map.of("enabled",open,"packages",open&&!privateAcceptance?paymentMethods.legacyPackages().stream().map(PaymentCatalog.Package::asMap).toList():List.of(),
            "paymentMethods",methods);
    }

    /** Internal first-payment verification uses authenticated principals, never a request owner field. */
    private boolean purchaseAllowed(CreatorPrincipal creator) {
        if(acceptingNewOrders)return true;
        if(creator==null||creator.id()==null||canaryOwnerIds==null||canaryOwnerIds.isBlank())return false;
        String[] owners=canaryOwnerIds.split(",",-1);
        if(owners.length>3)return false;
        for(String owner:owners)if(!owner.trim().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))return false;
        return Arrays.stream(owners).map(String::trim).anyMatch(creator.id()::equals);
    }

    public Map<String,Object> create(CreatorPrincipal creator, String packageId) {
        return create(creator,packageId,null);
    }

    public Map<String,Object> create(CreatorPrincipal creator, String packageId,String paymentMethod) {
        if (!schemaReady() || !purchaseAllowed(creator)) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Stripe 充值暂未开放");
        if(creator==null||creator.id()==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if(PaymentAcceptanceOffers.isAcceptance(packageId)&&acceptingNewOrders)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"PAYMENT_ACCEPTANCE_CANARY_ONLY");
        if(!acceptingNewOrders&&acceptanceOffers.configuredFor(creator.id())&&!PaymentAcceptanceOffers.isAcceptance(packageId))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"PAYMENT_ACCEPTANCE_PRIVATE_SKU_REQUIRED");
        var offer=PaymentAcceptanceOffers.isAcceptance(packageId)
            ?paymentMethods.selectAcceptance(acceptanceOffers.select(creator.id(),packageId,paymentMethod),paymentMethod)
            :paymentMethods.select(packageId,paymentMethod);
        gateway.verifyNewPurchase(StripePaymentMethods.checkoutMethodForStoredOffer(offer.id()),offer.currency());
        var selected=new PaymentCatalog.Package(offer.id(),offer.title(),offer.amount(),offer.currency(),offer.credits());
        String id=UUID.randomUUID().toString();
        reserve(creator.id(),id,selected);
        return createOrResume(creator.id(),id);
    }

    private void reserve(String owner,String id,PaymentCatalog.Package pack) {
        tx.executeWithoutResult(s->{
            if(db.queryForList("SELECT id FROM creator WHERE id=? FOR UPDATE",owner).isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
            Integer identities=db.queryForObject("SELECT (SELECT COUNT(*) FROM creator_google_identity WHERE creator_id=?) + (SELECT COUNT(*) FROM creator_oidc_identity WHERE creator_id=?)",Integer.class,owner,owner);
            if(identities==null||identities==0) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"请先登录账号");
            acceptanceOffers.requireReservation(db,owner,pack.id(),provider());
            Integer pending;
            if(legacyOrdersReadOnly){
                var boundary=db.queryForObject("SELECT activated_at FROM p0_credit_cutover_state WHERE id=1 AND status='ACTIVE'",java.sql.Timestamp.class);
                if(boundary==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"积分切换尚未完成");
                pending=db.queryForObject("SELECT COUNT(*) FROM payment_order WHERE creator_id=? AND status IN ('CREATING','CREATION_UNKNOWN','PENDING') AND created_at>?",Integer.class,owner,boundary);
            }else pending=db.queryForObject("SELECT COUNT(*) FROM payment_order WHERE creator_id=? AND status IN ('CREATING','CREATION_UNKNOWN','PENDING')",Integer.class,owner);
            if(pending!=null&&pending>0) throw new ResponseStatusException(HttpStatus.CONFLICT,"已有待核对订单，请先查看订单状态");
            db.update("INSERT INTO payment_order(id,creator_id,package_id,amount,currency,credits,status,created_at,provider) VALUES(?,?,?,?,?,?,?,CURRENT_TIMESTAMP(6),?)",
                id,owner,pack.id(),pack.amount(),pack.currency(),pack.credits(),"CREATING",gateway.provider());
            // Capture the original merchant and financial snapshot before any remote POST.
            // This joins the order transaction, so no order can lose its recovery pointer.
            enqueueOrderReconciliation(db.queryForMap("SELECT * FROM payment_order WHERE id=? AND provider=?",id,provider()),true);
        });
    }

    private Map<String,Object> createOrResume(String owner,String id) {
        var row=ownedOrder(owner,id);
        String status=(String)row.get("status");
        if ("PAID".equals(status)) return orderView(id,row);
        if (!Set.of("CREATING","PENDING").contains(status)) throw new ResponseStatusException(HttpStatus.CONFLICT,"订单已结束或需要人工核对");
        String sessionId=(String)row.get("provider_link_id");
        JsonNode session;
        if (sessionId==null) {
            Instant created=((java.sql.Timestamp)row.get("created_at")).toInstant();
            // Stripe idempotency records are time-bounded. Never issue a new remote create after the
            // safe replay window; that old order must be reconciled manually instead of risking a duplicate charge.
            if (Duration.between(created,Instant.now()).compareTo(Duration.ofHours(20))>=0)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"创建结果未知，订单需要人工核对");
            try {
                String method=StripePaymentMethods.checkoutMethodForStoredOffer((String)row.get("package_id"));
                // Preserve the original card API and its exact idempotency request for legacy orders.
                session="card".equals(method)
                    ?gateway.createSession(id,"Qingfeng music credits",(BigDecimal)row.get("amount"),(String)row.get("currency"))
                    :gateway.createSession(id,"Qingfeng music credits",(BigDecimal)row.get("amount"),(String)row.get("currency"),method);
            } catch (StripeCheckoutGateway.StripeRemoteException failure) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"支付创建结果待核对，请勿重复下单");
            }
            sessionId=text(session.path("id"));
            String url=text(session.path("url"));
            if (sessionId.isBlank()||!trustedCheckoutUrl(url)) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Stripe 返回的 Checkout Session 无效");
            String finalSessionId=sessionId;
            db.update("UPDATE payment_order SET provider_link_id=?,status=CASE WHEN status IN ('CREATING','CREATION_UNKNOWN') THEN 'PENDING' ELSE status END WHERE id=? AND provider=? AND (provider_link_id IS NULL OR provider_link_id=?)",
                sessionId,id,gateway.provider(),sessionId);
            row=ownedOrder(owner,id);
            if (!finalSessionId.equals(row.get("provider_link_id"))) throw new ResponseStatusException(HttpStatus.CONFLICT,"支付订单关联不匹配");
            return Map.of("orderId",id,"status",row.get("status"),"checkoutUrl",url);
        }
        session=gateway.retrieveSession(sessionId);
        settleIfPaid(id,sessionId,session);
        row=ownedOrder(owner,id);
        if ("PAID".equals(row.get("status"))) return orderView(id,row);
        String url=text(session.path("url"));
        if ("open".equals(text(session.path("status")))&&trustedCheckoutUrl(url))
            return Map.of("orderId",id,"status",row.get("status"),"checkoutUrl",url);
        if ("expired".equals(text(session.path("status")))) {
            db.update("UPDATE payment_order SET status='CANCELLED' WHERE id=? AND provider=? AND status IN ('CREATING','PENDING')",id,gateway.provider());
            row=ownedOrder(owner,id);
        }
        return orderView(id,row);
    }

    public Map<String,Object> order(CreatorPrincipal creator,String id) {
        var row=ownedOrder(creator.id(),id);
        String session=(String)row.get("provider_link_id");
        if (session!=null && !"PAID".equals(row.get("status"))) {
            try { settleIfPaid(id,session,gateway.retrieveSession(session)); }
            catch (StripeCheckoutGateway.StripeRemoteException unavailable) { /* show local state; reconciliation continues */ }
            row=ownedOrder(creator.id(),id);
        }
        return orderView(id,row);
    }

    public Map<String,Object> resume(CreatorPrincipal creator,String id) {
        if (!schemaReady()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Stripe 订单核对暂不可用");
        return createOrResume(creator.id(),id);
    }

    public Map<String,Object> orders(CreatorPrincipal creator) {
        var rows=db.query("SELECT id,status,credits,created_at,amount,currency,package_id FROM payment_order WHERE creator_id=? AND provider=? ORDER BY created_at DESC,id DESC LIMIT 50",
            (rs,n)->Map.<String,Object>of("orderId",rs.getString(1),"status",rs.getString(2),"credits",rs.getLong(3),"createdAt",rs.getTimestamp(4).toInstant().toString(),
                "amount",rs.getBigDecimal(5),"currency",rs.getString(6),"packageId",rs.getString(7),"paymentMethod",StripePaymentMethods.checkoutMethodForStoredOffer(rs.getString(7))),creator.id(),gateway.provider());
        return Map.of("orders",rows);
    }

    public Map<String,Object> webhook(String signature,byte[] rawBody) {
        // Authenticate the raw body before interpreting any pointer. Invalid supported input
        // stays a client error even when the durable settlement schema is unavailable.
        JsonNode event=gateway.verifyWebhook(rawBody,signature);
        String type=text(event.path("type"));
        JsonNode object=event.path("data").path("object");
        String resource=text(object.path("id"));
        if (EVENTS.contains(type)&&resource.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Stripe event resource id missing");
        if (!schemaReady()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Stripe webhook settlement is not ready");
        if (!EVENTS.contains(type)) return Map.of("received",true,"ignored",true);
        String eventId=text(event.path("id"));
        // Stable ordering keeps duplicate event fingerprints identical across JVM restarts.
        Map<String,String> pointer=new TreeMap<>(Map.of("event",eventId,"resource",resource,"provider","STRIPE",
            "merchant",gateway.merchantId(),"environment",gateway.environment()));
        try {
            inbox.acceptVerified(provider(),gateway.merchantId(),gateway.environment(),eventId,type,json.writeValueAsString(pointer));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Stripe event could not be durably recorded");
        }
        return Map.of("received",true);
    }

    /** Compatibility alias; the sole durable worker is the shared PaymentInboxRunner. */
    public boolean reconcileOne(){return processNextEvent();}
    @Override public boolean processNextEvent() {
        if (!schemaReady()) return false;
        var claimed=inbox.claim(provider(),gateway.merchantId(),gateway.environment());
        if(claimed.isEmpty())return false;
        var delivery=claimed.get();
        try{
            JsonNode pointer=json.readTree(delivery.payload());
            if (!"STRIPE".equals(text(pointer.path("provider")))
                || !gateway.merchantId().equals(text(pointer.path("merchant")))
                || !gateway.environment().equals(text(pointer.path("environment")))
                || !delivery.eventId().equals(text(pointer.path("event"))))
                throw new IllegalStateException("Stripe merchant or environment changed");
            String resource=text(pointer.path("resource")),kind=delivery.type();
            if(resource.isBlank()||!(EVENTS.contains(kind)||ORDER_RECONCILE.equals(kind)))throw new IllegalStateException("Unsupported Stripe event");
            Resolution result;
            if(ORDER_RECONCILE.equals(kind))result=reconcileOrder(pointer);
            else if (kind.startsWith("checkout.session.")) result=reconcileSession(kind,resource);
            else if (kind.startsWith("refund.")) result=reconcileRefund(resource);
            else if (kind.startsWith("charge.dispute.")) result=reconcileDispute(resource);
            else throw new IllegalStateException("Unsupported Stripe event");
            if(result==null||!result.verified()||result.evidenceId()==null||result.evidenceId().isBlank()||result.apply()==null||result.ownerId()==null||result.ownerId().isBlank())throw new IllegalStateException("AUTHORITATIVE_STATE_UNKNOWN");
            if(result.pending())inbox.waitForProvider(delivery,result.ownerId(),result.apply(),result.evidenceId());
            else inbox.completeOwned(delivery,result.ownerId(),result.apply(),result.evidenceId());
        }catch(RuntimeException failed){inbox.retry(delivery);}
        return true;
    }

    /**
     * Repair missing callbacks without requiring a browser or creating another checkout.
     * Each pass has ten local candidates; the durable Inbox owns retries, leases and review.
     */
    @Override public void reconcileOrders(){
        if(!schemaReady())return;
        var candidates=db.queryForList("SELECT * FROM payment_order WHERE provider=? AND status IN ('CREATING','CREATION_UNKNOWN','PENDING') AND created_at<=TIMESTAMPADD(MINUTE,-5,CURRENT_TIMESTAMP) AND (reconcile_after IS NULL OR reconcile_after<=CURRENT_TIMESTAMP) ORDER BY reconcile_after,created_at,id LIMIT 10",provider());
        for(var row:candidates){
            String orderId=(String)row.get("id");
            if(db.update("UPDATE payment_order SET reconcile_after=TIMESTAMPADD(MINUTE,5,CURRENT_TIMESTAMP) WHERE id=? AND provider=? AND status IN ('CREATING','CREATION_UNKNOWN','PENDING') AND (reconcile_after IS NULL OR reconcile_after<=CURRENT_TIMESTAMP)",orderId,provider())!=1)continue;
            String eventId=orderReconcileId(orderId);
            var known=db.queryForList("SELECT merchant_scope,env_scope FROM payment_webhook_inbox WHERE provider=? AND event_id=?",provider(),eventId);
            if(!known.isEmpty()){
                // Never adopt a previously bound order after changing the merchant secret.
                if(known.size()!=1||!gateway.merchantId().equals(known.get(0).get("merchant_scope"))||!gateway.environment().equals(known.get(0).get("env_scope")))
                    markReconcileOrder(row,"STRIPE_ORDER_SCOPE_REVIEW_REQUIRED",false);
                continue;
            }
            // Historical orders did not record the original merchant. Preserve them for
            // explicit evidence review rather than manufacture that missing snapshot.
            enqueueOrderReconciliation(row,false);
        }
    }
    private static String orderReconcileId(String order){return "reconcile-order-"+order;}
    private void enqueueOrderReconciliation(Map<String,Object> row,boolean originalScope){
        String eventId=orderReconcileId((String)row.get("id"));
        var pointer=new TreeMap<String,Object>();
        pointer.put("event",eventId);pointer.put("resource",row.get("id"));pointer.put("provider","STRIPE");
        pointer.put("merchant",gateway.merchantId());pointer.put("environment",gateway.environment());
        pointer.put("owner",row.get("creator_id"));pointer.put("amount",((BigDecimal)row.get("amount")).toPlainString());
        pointer.put("currency",row.get("currency"));pointer.put("credits",((Number)row.get("credits")).longValue());
        pointer.put("package",row.get("package_id"));pointer.put("created",((java.sql.Timestamp)row.get("created_at")).toInstant().toEpochMilli());
        pointer.put("original_scope",originalScope);
        inbox.acceptVerified(provider(),gateway.merchantId(),gateway.environment(),eventId,ORDER_RECONCILE,json.writeValueAsString(pointer));
    }
    private Resolution reconcileOrder(JsonNode pointer){
        String id=text(pointer.path("resource")),owner=text(pointer.path("owner"));
        var row=ownedOrder(owner,id);
        if(!pointer.path("original_scope").isBoolean()||!pointer.path("original_scope").asBoolean())
            return Resolution.pending("stripe-order-original-scope-unverified:"+id,()->markReconcileOrder(row,"STRIPE_ORDER_SCOPE_REVIEW_REQUIRED",false),owner);
        if(!Objects.equals(row.get("id"),id)||!Objects.equals(row.get("creator_id"),owner)
            ||!Objects.equals(row.get("currency"),text(pointer.path("currency")))
            ||!Objects.equals(row.get("package_id"),text(pointer.path("package")))
            ||((BigDecimal)row.get("amount")).compareTo(new BigDecimal(text(pointer.path("amount"))))!=0
            ||((Number)row.get("credits")).longValue()!=exactInteger(pointer.path("credits"))
            ||((java.sql.Timestamp)row.get("created_at")).toInstant().toEpochMilli()!=exactInteger(pointer.path("created")))
            throw new IllegalStateException("Stripe original order snapshot changed");
        if("PAID".equals(row.get("status")))return new Resolution(true,"stripe-order-already-paid:"+id,()->{},owner);
        if(!Set.of("CREATING","CREATION_UNKNOWN","PENDING").contains(row.get("status")))
            return new Resolution(true,"stripe-order-terminal:"+id,()->{},owner);
        String session=(String)row.get("provider_link_id");
        if(session==null&&"CREATING".equals(row.get("status"))
            &&Duration.between(((java.sql.Timestamp)row.get("created_at")).toInstant(),Instant.now(reconciliationClock)).compareTo(Duration.ofMinutes(5))<0)
            return Resolution.pending("stripe-order-create-in-flight:"+id,()->{},owner);
        try{
            if(session==null){
                session=findOriginalSession(row);
                if(session==null)return Resolution.pending("stripe-order-create-result-unknown:"+id,()->markReconcileOrder(row,"STRIPE_CREATE_RESULT_UNKNOWN",true),owner);
            }
            var fact=gateway.retrieveSession(session);
            if(!fact.path("livemode").isBoolean()||!fact.path("amount_total").isIntegralNumber())
                throw new IllegalStateException("Stripe reconciliation financial fields missing");
            verifySession(row,fact);
            if(!session.equals(text(fact.path("id"))))throw new IllegalStateException("Stripe reconciliation session identity mismatch");
            String resolvedSession=session;
            if("complete".equals(text(fact.path("status")))&&"paid".equals(text(fact.path("payment_status"))))
                return new Resolution(true,"stripe-order-reconciled:"+session,()->settle(id,resolvedSession,fact),owner);
            if("expired".equals(text(fact.path("status")))&&"unpaid".equals(text(fact.path("payment_status"))))
                return new Resolution(true,"stripe-order-expired:"+session,()->linkReconciledSession(row,resolvedSession,"CANCELLED"),owner);
            if("unpaid".equals(text(fact.path("payment_status")))&&Set.of("open","complete").contains(text(fact.path("status"))))
                return Resolution.pending("stripe-order-awaiting-payment:"+session,()->linkReconciledSession(row,resolvedSession,"PENDING"),owner);
            return Resolution.pending("stripe-order-provider-state-unknown:"+session,()->markReconcileOrder(row,"STRIPE_RECONCILE_STATE_UNKNOWN",false),owner);
        }catch(StripeCheckoutGateway.StripeRemoteException unavailable){
            // A 404, timeout or provider error cannot prove that no funds were received.
            markReconcileOrder(row,"STRIPE_RECONCILE_PROVIDER_UNKNOWN",true);
            throw unavailable;
        }
    }
    private String findOriginalSession(Map<String,Object> row){
        Instant created=((java.sql.Timestamp)row.get("created_at")).toInstant();
        var window=new StripeCheckoutGateway.InstantRange(Math.max(0,created.minusSeconds(60).getEpochSecond()),created.plus(Duration.ofHours(20)).plusSeconds(60).getEpochSecond());
        String cursor=null,match=null;var cursors=new HashSet<String>();
        for(int page=0;page<3;page++){
            var response=gateway.sessionsCreatedBetween(window,cursor);
            if(response==null||!"list".equals(text(response.path("object")))||!response.path("data").isArray()
                ||response.path("data").size()>100||!response.path("has_more").isBoolean())
                throw new IllegalStateException("Incomplete Stripe Checkout listing");
            for(JsonNode candidate:response.path("data")){
                if(Objects.equals(row.get("id"),text(candidate.path("client_reference_id")))||Objects.equals(row.get("id"),text(candidate.path("metadata").path("order_id")))){
                    verifySession(row,candidate);
                    String found=text(candidate.path("id"));
                    if(!found.matches("cs_[A-Za-z0-9_]{1,125}")||match!=null&&!match.equals(found))
                        throw new IllegalStateException("Ambiguous Stripe Checkout association");
                    match=found;
                }
            }
            if(!response.path("has_more").asBoolean())return match;
            if(response.path("data").isEmpty())throw new IllegalStateException("Invalid Stripe Checkout cursor");
            cursor=text(response.path("data").get(response.path("data").size()-1).path("id"));
            if(!cursor.matches("cs_[A-Za-z0-9_]{1,125}")||!cursors.add(cursor))throw new IllegalStateException("Invalid Stripe Checkout cursor");
        }
        // A partial listing never establishes uniqueness or absence.
        return null;
    }
    private void markReconcileOrder(Map<String,Object> row,String code,boolean unknown){
        tx.executeWithoutResult(t->{
            String owner=(String)row.get("creator_id");db.queryForList("SELECT id FROM creator WHERE id=? FOR UPDATE",owner);
            db.update("UPDATE payment_order SET failure_code=?,status=CASE WHEN ? AND provider_link_id IS NULL AND status='CREATING' THEN 'CREATION_UNKNOWN' ELSE status END WHERE id=? AND creator_id=? AND provider=? AND status IN ('CREATING','CREATION_UNKNOWN','PENDING')",code,unknown,row.get("id"),owner,provider());
        });
    }
    private void linkReconciledSession(Map<String,Object> row,String session,String status){
        String owner=(String)row.get("creator_id");db.queryForList("SELECT id FROM creator WHERE id=? FOR UPDATE",owner);
        var current=ownedOrder(owner,(String)row.get("id"));
        if(current.get("provider_link_id")!=null&&!session.equals(current.get("provider_link_id")))throw new IllegalStateException("Stripe reconciled link conflict");
        db.update("UPDATE payment_order SET provider_link_id=?,status=?,failure_code=NULL WHERE id=? AND creator_id=? AND provider=? AND status IN ('CREATING','CREATION_UNKNOWN','PENDING') AND (provider_link_id IS NULL OR provider_link_id=?)",session,status,row.get("id"),owner,provider(),session);
    }

    private Resolution reconcileSession(String kind,String sessionId) {
        JsonNode session=gateway.retrieveSession(sessionId);
        String orderId=text(session.path("metadata").path("order_id"));
        if (orderId.isBlank()||!orderId.equals(text(session.path("client_reference_id")))||!sessionId.equals(text(session.path("id"))))
            throw new IllegalStateException("Stripe Checkout identity mismatch");
        var rows=db.queryForList("SELECT * FROM payment_order WHERE id=? AND provider=?",orderId,gateway.provider());
        if (rows.size()!=1) throw new IllegalStateException("Stripe order unresolved");
        var row=rows.get(0);
        verifySession(row,session);
        String providerStatus=text(session.path("status"));
        String paymentStatus=text(session.path("payment_status"));
        if ("complete".equals(providerStatus)&&"paid".equals(paymentStatus)) {
            String intent=idOf(session.path("payment_intent"));
            if (intent.isBlank()) throw new IllegalStateException("Stripe PaymentIntent missing");
            String owner=(String)row.get("creator_id");
            return new Resolution(true,"stripe-session:"+sessionId,()->settle(orderId,sessionId,session),owner);
        }
        if ("checkout.session.expired".equals(kind)&&"expired".equals(providerStatus)) {
            String owner=(String)row.get("creator_id");
            return new Resolution(true,"stripe-session-expired:"+sessionId,()->db.update("UPDATE payment_order SET status='CANCELLED' WHERE id=? AND provider=? AND status IN ('CREATING','PENDING')",orderId,gateway.provider()),owner);
        }
        if ("checkout.session.async_payment_failed".equals(kind)&&"complete".equals(providerStatus)&&"unpaid".equals(paymentStatus)) {
            String owner=(String)row.get("creator_id");
            return new Resolution(true,"stripe-session-failed:"+sessionId,()->db.update("UPDATE payment_order SET status='FAILED' WHERE id=? AND provider=? AND status IN ('CREATING','PENDING')",orderId,gateway.provider()),owner);
        }
        if("wechat_pay".equals(StripePaymentMethods.checkoutMethodForStoredOffer((String)row.get("package_id")))
            &&"unpaid".equals(paymentStatus)&&Set.of("open","complete").contains(providerStatus)) {
            // A shopper completing the UI is not proof that the wallet payment settled.
            // Keep the durable pointer without spending the technical-failure retry budget.
            return Resolution.pending("stripe-session-awaiting-payment:"+sessionId,()->{},(String)row.get("creator_id"));
        }
        return new Resolution(false,null,null);
    }

    private Resolution reconcileRefund(String refundId) {
        JsonNode refund=gateway.retrieveRefund(refundId);
        if (!refundId.equals(text(refund.path("id")))) throw new IllegalStateException("Stripe refund identity mismatch");
        String refundStatus=text(refund.path("status"));
        boolean failed=Set.of("failed","canceled").contains(refundStatus);
        boolean pending=Set.of("pending","requires_action").contains(refundStatus);
        if (!"succeeded".equals(refundStatus)&&!pending&&!failed)
            return new Resolution(false,null,null);
        String intent=idOf(refund.path("payment_intent"));
        if (intent.isBlank()) {
            String charge=idOf(refund.path("charge"));
            if (charge.isBlank()) throw new IllegalStateException("Stripe refund payment identity missing");
            intent=idOf(gateway.retrieveCharge(charge).path("payment_intent"));
        }
        var row=paymentByIntent(intent);
        long amount=exactInteger(refund.path("amount"));
        String currency=text(refund.path("currency")).toUpperCase(Locale.ROOT);
        if (amount<=0||!currency.equals(row.get("currency"))) throw new IllegalStateException("Stripe refund amount/currency mismatch");
        String owner=(String)row.get("creator_id"),orderId=(String)row.get("id");
        String paymentIntent=intent;
        if(pending)return Resolution.pending("stripe-refund-pending:"+refundId+":"+refundStatus,()->{},owner);
        if(failed){
            var recorded=db.queryForList("SELECT * FROM p0_credit_refund WHERE provider='STRIPE' AND merchant=? AND environment=? AND external_id=?",gateway.merchantId(),gateway.environment(),refundId);
            String evidence="stripe-refund-terminal:"+refundId+":"+refundStatus;
            if(recorded.isEmpty())return new Resolution(true,evidence,()->fencedLedger().recordFailedRefund(
                "STRIPE",gateway.merchantId(),gateway.environment(),refundId,owner,orderId,paymentIntent,amount,currency,refundStatus,evidence),owner);
            String chargeId=idOf(refund.path("charge"));
            String withdrawal=idOf(refund.path("balance_transaction")),reversal=idOf(refund.path("failure_balance_transaction"));
            // A failed status alone never reverses an already-applied successful refund.
            if(chargeId.isBlank()||withdrawal.isBlank()||reversal.isBlank())
                return Resolution.pending(evidence+":awaiting-funds",()->{},owner);
            var proof=StripeRefundReturnProof.verify(gateway.isLive(),refundId,paymentIntent,amount,currency,
                ((BigDecimal)row.get("amount")).movePointRight(2).longValueExact(),refund,gateway.retrieveCharge(chargeId),
                gateway.retrieveBalanceTransaction(withdrawal),gateway.retrieveBalanceTransaction(reversal));
            // A proven native refund reversal ends the refund's economic effect.
            // Pending merchant settlement controls payout availability, not customer rights.
            // Identity, exact principal and terminal refund status were all verified above.
            return new Resolution(true,proof.transactionId(),()->fencedLedger().compensateRefundReturnedFunds(
                "STRIPE",gateway.merchantId(),gateway.environment(),proof.transactionId(),refundId,owner,orderId,paymentIntent,amount,currency,refundStatus,proof.evidence()),owner);
        }
        return new Resolution(true,"stripe-refund:"+refundId,()->fencedLedger().paymentRefund("STRIPE",gateway.merchantId(),gateway.environment(),refundId,owner,orderId,paymentIntent,amount,currency),owner);
    }

    private Resolution reconcileDispute(String disputeId) {
        JsonNode dispute=gateway.retrieveDispute(disputeId);
        if (!disputeId.equals(text(dispute.path("id")))) throw new IllegalStateException("Stripe dispute identity mismatch");
        String intent=idOf(dispute.path("payment_intent"));
        if (intent.isBlank()) {
            String charge=idOf(dispute.path("charge"));
            if (charge.isBlank()) throw new IllegalStateException("Stripe dispute payment identity missing");
            intent=idOf(gateway.retrieveCharge(charge).path("payment_intent"));
        }
        var row=paymentByIntent(intent);
        long amount=exactInteger(dispute.path("amount"));
        String currency=text(dispute.path("currency")).toUpperCase(Locale.ROOT);
        if (amount<=0||!currency.equals(row.get("currency"))) throw new IllegalStateException("Stripe dispute amount/currency mismatch");
        String status=text(dispute.path("status"));
        String owner=(String)row.get("creator_id"),orderId=(String)row.get("id"),capture=(String)row.get("provider_capture_id");
        if("won".equals(status)){
            var prior=db.queryForList("SELECT * FROM p0_credit_dispute WHERE provider='STRIPE' AND merchant=? AND environment=? AND external_id=?",gateway.merchantId(),gateway.environment(),disputeId);
            if(prior.size()==1&&"LOST".equals(prior.get(0).get("state"))&&prior.get(0).get("loss_minor") instanceof Number knownLoss&&knownLoss.longValue()>0){
                var original=prior.get(0);
                if(!owner.equals(original.get("owner_id"))||!orderId.equals(original.get("order_id"))||!capture.equals(original.get("capture_id"))
                    ||exactInteger(dispute.path("amount"))!=((Number)original.get("amount_minor")).longValue()||!currency.equals(original.get("currency")))
                    throw new IllegalStateException("Stripe returned funds dispute identity mismatch");
                long returned=knownLoss.longValue();
                var stored=db.queryForList("SELECT * FROM p0_stripe_dispute_proof WHERE provider='STRIPE' AND merchant=? AND environment=? AND dispute_id=?",gateway.merchantId(),gateway.environment(),disputeId);
                VerifiedDisputeReturn movement;
                if(!stored.isEmpty()){
                    var proof=stored.get(0);
                    if(!owner.equals(proof.get("owner_id"))||!orderId.equals(proof.get("order_id"))||!capture.equals(proof.get("capture_id"))
                        ||returned!=((Number)proof.get("loss_minor")).longValue())throw new IllegalStateException("Stripe stored principal proof identity conflict");
                    assertNoOverlappingCrossSettlement(orderId,disputeId);
                    var known=new StripePrincipalProof.Loss(returned,(String)proof.get("withdrawal_id"),(String)proof.get("charge_balance_id"),
                        (String)proof.get("settlement_currency"),((Number)proof.get("settlement_principal_minor")).longValue(),"");
                    var verified=StripePrincipalProof.returned(expectedScope(row),principalOrder(row),principalEvidence(dispute),known);
                    movement=new VerifiedDisputeReturn(verified.transactionId(),verified.available());
                }else movement=verifiedDisputeReturn(dispute,currency,returned);
                if(!movement.available())return Resolution.pending(movement.transactionId(),()->{},owner);
                return new Resolution(true,movement.transactionId(),()->{
                    if(!stored.isEmpty())assertNoOverlappingCrossSettlement(orderId,disputeId);
                    fencedLedger().compensateReturnedFunds("STRIPE",gateway.merchantId(),gateway.environment(),movement.transactionId(),disputeId,owner,orderId,capture,returned,currency,movement.transactionId());
                },owner);
            }
        }
        String state;Long loss=null;StripePrincipalProof.Loss principal=null;
        if (Set.of("won","prevented","warning_closed").contains(status)) state="WON";
        else if ("lost".equals(status)) {
            state="LOST";
            if(crossSettlement(dispute,currency)){
                // Only an exact, whole-principal native pairing can prove a cross-settlement loss.
                // Missing or ambiguous facts leave this order frozen; no spot-rate conversion.
                try{
                    assertNoOverlappingCrossSettlement(orderId,disputeId);
                    principal=StripePrincipalProof.loss(expectedScope(row),principalOrder(row),principalEvidence(dispute));
                    loss=principal.lossMinor();
                }catch(IllegalStateException ambiguous){loss=null;}
            }else loss=verifiedDisputeLoss(dispute,currency);
            // Without an attributable balance movement, keep the order frozen as UNKNOWN.
            if (loss==null) state="UNKNOWN";
        } else if(Set.of("warning_needs_response","warning_under_review","needs_response","under_review").contains(status)) state="UNDER_REVIEW";
        else state="UNKNOWN";
        long version=Math.max(1,Instant.now().toEpochMilli());
        Long finalLoss=loss;String finalState=state;var finalPrincipal=principal;
        Runnable apply=()->{
            if(finalPrincipal!=null){assertNoOverlappingCrossSettlement(orderId,disputeId);persistPrincipalProof(row,disputeId,finalPrincipal);}
            fencedLedger().paymentDispute("STRIPE",gateway.merchantId(),gateway.environment(),disputeId,owner,orderId,capture,amount,currency,version,finalState,finalLoss);
        };
        String evidence="stripe-dispute:"+disputeId+":"+status;
        if("UNKNOWN".equals(state))return Resolution.pending(evidence,apply,owner);
        // Known UNDER_REVIEW is an accepted event; a later lifecycle webhook remains required.
        return new Resolution(true,evidence,apply,owner);
    }

    private boolean crossSettlement(JsonNode dispute,String currency){
        for(JsonNode movement:dispute.path("balance_transactions"))
            if(!text(movement.path("currency")).isBlank()&&!currency.equalsIgnoreCase(text(movement.path("currency"))))return true;
        return false;
    }
    private StripePrincipalProof.Scope expectedScope(Map<String,Object> row){
        var binding=db.queryForMap("SELECT * FROM p0_credit_payment_binding WHERE order_id=?",row.get("id"));
        if(!"STRIPE".equals(binding.get("provider"))||!Objects.equals(row.get("creator_id"),binding.get("owner_id"))
            ||!Objects.equals(row.get("provider_capture_id"),binding.get("capture_id")))throw new IllegalStateException("Stripe local payment binding mismatch");
        return new StripePrincipalProof.Scope((String)binding.get("merchant"),(String)binding.get("environment"),"live".equals(binding.get("environment")));
    }
    private StripePrincipalProof.Order principalOrder(Map<String,Object> row){
        return new StripePrincipalProof.Order((String)row.get("id"),(String)row.get("provider_capture_id"),(String)row.get("currency"),
            ((BigDecimal)row.get("amount")).movePointRight(2).longValueExact());
    }
    private StripePrincipalProof.Evidence principalEvidence(JsonNode dispute){
        String chargeId=idOf(dispute.path("charge"));
        if(chargeId.isBlank())throw new IllegalStateException("Stripe original Charge missing");
        JsonNode charge=gateway.retrieveCharge(chargeId);
        String balance=idOf(charge.path("balance_transaction"));
        if(balance.isBlank())throw new IllegalStateException("Stripe original settlement missing");
        return new StripePrincipalProof.Evidence(new StripePrincipalProof.Scope(gateway.merchantId(),gateway.environment(),gateway.isLive()),dispute,charge,
            gateway.retrieveBalanceTransaction(balance),gateway.refundsForCharge(chargeId),gateway.disputesForCharge(chargeId));
    }
    private void assertNoOverlappingCrossSettlement(String order,String dispute){
        Long refunds=db.queryForObject("SELECT COUNT(*) FROM p0_credit_refund WHERE order_id=?",Long.class,order);
        Long others=db.queryForObject("SELECT COUNT(*) FROM p0_credit_dispute WHERE order_id=? AND external_id<>?",Long.class,order,dispute);
        if(refunds==null||refunds!=0||others==null||others!=0)throw new IllegalStateException("Stripe cross-settlement overlap requires manual verification");
    }
    private void persistPrincipalProof(Map<String,Object> order,String dispute,StripePrincipalProof.Loss proof){
        String digest;
        try{digest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(proof.evidenceSummary().getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        var existing=db.queryForList("SELECT * FROM p0_stripe_dispute_proof WHERE provider='STRIPE' AND merchant=? AND environment=? AND dispute_id=? FOR UPDATE",gateway.merchantId(),gateway.environment(),dispute);
        if(existing.isEmpty())db.update("INSERT INTO p0_stripe_dispute_proof(provider,merchant,environment,dispute_id,order_id,capture_id,owner_id,loss_minor,withdrawal_id,charge_balance_id,settlement_currency,settlement_principal_minor,evidence_sha256,created_at) VALUES('STRIPE',?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
            gateway.merchantId(),gateway.environment(),dispute,order.get("id"),order.get("provider_capture_id"),order.get("creator_id"),proof.lossMinor(),proof.withdrawalId(),proof.chargeBalanceId(),proof.settlementCurrency(),proof.settlementPrincipalMinor(),digest);
        else{
            var prior=existing.get(0);
            if(!Objects.equals(order.get("id"),prior.get("order_id"))||!Objects.equals(order.get("creator_id"),prior.get("owner_id"))
                ||!Objects.equals(order.get("provider_capture_id"),prior.get("capture_id"))||proof.lossMinor()!=((Number)prior.get("loss_minor")).longValue()
                ||!proof.withdrawalId().equals(prior.get("withdrawal_id"))||!proof.chargeBalanceId().equals(prior.get("charge_balance_id"))
                ||!proof.settlementCurrency().equals(prior.get("settlement_currency"))||proof.settlementPrincipalMinor()!=((Number)prior.get("settlement_principal_minor")).longValue())
                throw new IllegalStateException("Stripe principal proof conflict");
        }
    }

    private record VerifiedDisputeReturn(String transactionId,boolean available){}
    private VerifiedDisputeReturn verifiedDisputeReturn(JsonNode dispute,String currency,long loss){
        if(!"dispute".equals(text(dispute.path("object")))||!dispute.path("livemode").isBoolean()||dispute.path("livemode").asBoolean()!=gateway.isLive())
            throw new IllegalStateException("Stripe returned funds environment mismatch");
        JsonNode balances=dispute.path("balance_transactions");
        // This narrow path supports the native withdrawal/reinstatement pair only.
        if(!balances.isArray()||balances.size()!=2)throw new IllegalStateException("Stripe complete returned funds evidence required");
        String disputeId=text(dispute.path("id")),creditId=null,creditStatus=null;long negative=0,positive=0;int credits=0;
        Set<String> identities=new HashSet<>();
        for(JsonNode balance:balances){
            String id=text(balance.path("id"));JsonNode exchange=balance.get("exchange_rate");
            if(!id.matches("txn_[A-Za-z0-9_]+")||id.length()>128||!identities.add(id)
                ||!"balance_transaction".equals(text(balance.path("object")))||!"adjustment".equals(text(balance.path("type")))
                ||!disputeId.equals(idOf(balance.path("source")))||!currency.equalsIgnoreCase(text(balance.path("currency")))
                ||exchange==null||!exchange.isNull())throw new IllegalStateException("Stripe returned funds attribution is ambiguous");
            long amount=exactInteger(balance.path("amount")),fee=exactInteger(balance.path("fee")),net=exactInteger(balance.path("net"));
            if(Math.subtractExact(amount,fee)!=net)throw new IllegalStateException("Stripe returned funds fee reconciliation mismatch");
            // amount is principal; fee and net must never become the credit restoration base.
            if(amount<0)negative=Math.addExact(negative,amount);
            else if(amount>0){positive=Math.addExact(positive,amount);credits++;creditId=id;creditStatus=text(balance.path("status"));}
            else throw new IllegalStateException("Stripe returned funds principal missing");
        }
        if(negative!=Math.negateExact(loss)||positive!=loss||credits!=1||Math.addExact(negative,positive)!=0
            ||!("available".equals(creditStatus)||"pending".equals(creditStatus)))
            throw new IllegalStateException("Stripe full same-currency returned principal required");
        return new VerifiedDisputeReturn(creditId,"available".equals(creditStatus));
    }
    private static long exactInteger(JsonNode value){
        if(!value.isIntegralNumber())throw new IllegalStateException("Stripe financial integer required");
        return new java.math.BigInteger(value.toString()).longValueExact();
    }

    private Long verifiedDisputeLoss(JsonNode dispute,String currency) {
        JsonNode balances=dispute.path("balance_transactions");
        if (!balances.isArray()||balances.isEmpty()) return null;
        String disputeId=text(dispute.path("id"));long net=0;boolean attributed=false;
        for (JsonNode balance:balances) {
            String source=idOf(balance.path("source"));
            String balanceCurrency=text(balance.path("currency"));
            String type=text(balance.path("type"));
            if (!disputeId.equals(source)||!currency.equalsIgnoreCase(balanceCurrency)||type.contains("fee")) continue;
            if (!balance.path("amount").isIntegralNumber()) return null;
            net=Math.addExact(net,exactInteger(balance.path("amount")));attributed=true;
        }
        if (!attributed||net>0) return null;
        long result=Math.negateExact(net);
        return result<=exactInteger(dispute.path("amount"))?result:null;
    }

    private Map<String,Object> paymentByIntent(String intent) {
        if (intent==null||intent.isBlank()) throw new IllegalStateException("Stripe PaymentIntent unresolved");
        var rows=db.queryForList("SELECT * FROM payment_order WHERE provider=? AND provider_capture_id=?",gateway.provider(),intent);
        if (rows.size()!=1||!"PAID".equals(rows.get(0).get("status"))) throw new IllegalStateException("Stripe payment order unresolved");
        return rows.get(0);
    }

    private void settleIfPaid(String orderId,String sessionId,JsonNode session) {
        if ("complete".equals(text(session.path("status")))&&"paid".equals(text(session.path("payment_status")))) settle(orderId,sessionId,session);
    }

    private void settle(String id,String sessionId,JsonNode session) {
        // Resolve immutable ownership without a row lock, then follow the common owner -> order sequence.
        var candidates=db.queryForList("SELECT * FROM payment_order WHERE id=? AND provider=?",id,gateway.provider());
        if(candidates.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Stripe order not found");
        String owner=(String)candidates.get(0).get("creator_id");
        if(owner==null||owner.isBlank())throw new IllegalStateException("Stripe order owner unresolved");
        tx.executeWithoutResult(s->{
            if(db.queryForList("SELECT id FROM creator WHERE id=? FOR UPDATE",owner).size()!=1)throw new IllegalStateException("Stripe order owner unresolved");
            var rows=db.queryForList("SELECT * FROM payment_order WHERE id=? AND provider=? FOR UPDATE",id,gateway.provider());
            if(rows.size()!=1) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Stripe order not found");
            var row=rows.get(0);
            if(!owner.equals(row.get("creator_id")))throw new IllegalStateException("Stripe order owner changed");
            verifySession(row,session);
            if(!sessionId.equals(text(session.path("id")))||!"complete".equals(text(session.path("status")))||!"paid".equals(text(session.path("payment_status"))))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Stripe payment is not complete");
            String intent=idOf(session.path("payment_intent"));
            if(intent.isBlank()) throw new ResponseStatusException(HttpStatus.CONFLICT,"Stripe PaymentIntent missing");
            Object prior=row.get("provider_capture_id");
            if(prior!=null&&!intent.equals(prior)) throw new ResponseStatusException(HttpStatus.CONFLICT,"Stripe payment identity mismatch");
            String existingSession=(String)row.get("provider_link_id");
            if(existingSession!=null&&!sessionId.equals(existingSession)) throw new ResponseStatusException(HttpStatus.CONFLICT,"Stripe Checkout identity mismatch");
            if(!"PAID".equals(row.get("status"))) {
                ledger.credit((String)row.get("creator_id"),((Number)row.get("credits")).longValue(),"payment:"+id);
                db.update("UPDATE payment_order SET status='PAID',provider_link_id=?,provider_capture_id=? WHERE id=? AND provider=?",sessionId,intent,id,gateway.provider());
            }
            fencedLedger().bindPayment("STRIPE",gateway.merchantId(),gateway.environment(),(String)row.get("creator_id"),id,intent,(String)row.get("currency"));
        });
    }

    private void verifySession(Map<String,Object> row,JsonNode session) {
        long expected=((BigDecimal)row.get("amount")).movePointRight(2).longValueExact();
        if (!Objects.equals(row.get("id"),text(session.path("metadata").path("order_id")))
            || !Objects.equals(row.get("id"),text(session.path("client_reference_id")))
            || !"payment".equals(text(session.path("mode")))
            || !Objects.equals(((String)row.get("currency")).toLowerCase(Locale.ROOT),text(session.path("currency")))
            || session.path("amount_total").asLong(-1)!=expected
            || session.path("livemode").asBoolean(false)!=gateway.isLive())
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Stripe payment snapshot mismatch");
    }

    private Map<String,Object> ownedOrder(String owner,String id) {
        var rows=db.queryForList("SELECT * FROM payment_order WHERE id=? AND creator_id=? AND provider=?",id,owner,gateway.provider());
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return rows.get(0);
    }

    private Map<String,Object> orderView(String id,Map<String,Object> row) {
        var view=new LinkedHashMap<String,Object>();
        view.put("orderId",id);view.put("status",row.get("status"));view.put("credits",((Number)row.get("credits")).longValue());
        if(row.get("amount")!=null)view.put("amount",row.get("amount"));
        if(row.get("currency")!=null)view.put("currency",row.get("currency"));
        if(row.get("package_id")!=null){view.put("packageId",row.get("package_id"));view.put("paymentMethod",StripePaymentMethods.checkoutMethodForStoredOffer((String)row.get("package_id")));}
        return view;
    }

    private JdbcFencedCreditsLedger fencedLedger() {
        if (ledger instanceof JdbcFencedCreditsLedger fenced) return fenced;
        throw new IllegalStateException("Stripe settlement requires the fenced entitlement ledger");
    }

    private static boolean trustedCheckoutUrl(String value) {
        try {
            var uri=java.net.URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme())&&"checkout.stripe.com".equalsIgnoreCase(uri.getHost())
                &&uri.getUserInfo()==null&&uri.getPort()==-1;
        } catch (RuntimeException e) { return false; }
    }

    private static String idOf(JsonNode node) {
        if(node==null||node.isNull())return "";
        if(node.isString())return node.asString();
        return text(node.path("id"));
    }
    private static String text(JsonNode node) { return node==null||node.isNull()?"":node.asString(""); }
}
