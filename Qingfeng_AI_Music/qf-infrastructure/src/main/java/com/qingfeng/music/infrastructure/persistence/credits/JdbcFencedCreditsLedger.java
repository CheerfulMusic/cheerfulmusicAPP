package com.qingfeng.music.infrastructure.persistence.credits;

import com.qingfeng.music.credits.CreditsLedger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import com.qingfeng.music.common.exception.DomainException;
import java.math.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Protocol-3 owner-first rights writer. The journal, stored balance and batch rights commit together. */
public final class JdbcFencedCreditsLedger implements CreditsLedger {
    private final JdbcCreditsLedger delegate;
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final boolean mysql;
    private final JdbcCreditNativeIdentity nativeIdentity;
    public JdbcFencedCreditsLedger(CreditsLedger delegate,JdbcTemplate db,TransactionTemplate tx){
        if(!(delegate instanceof JdbcCreditsLedger actual))throw new IllegalArgumentException("stored-balance JDBC delegate required");
        this.delegate=actual;this.db=db;this.tx=tx;
        this.mysql=Boolean.TRUE.equals(db.execute((ConnectionCallback<Boolean>)connection->"MySQL".equals(connection.getMetaData().getDatabaseProductName())));
        this.nativeIdentity=new JdbcCreditNativeIdentity(db);
    }
    private void write(String owner,Runnable action){
        tx.executeWithoutResult(s->{
            if(mysql&&Integer.valueOf(1).equals(db.queryForObject("SELECT @qf_credit_nonfinancial_tx",Integer.class)))
                throw new IllegalStateException("financial writes are forbidden in independent nonfinancial transactions");
            if(owner==null||owner.isBlank()||db.queryForList("SELECT id FROM creator WHERE id=? FOR UPDATE",owner).isEmpty())
                throw new DomainException("CREDIT_OWNER_MISSING","账号不存在。");
            // Shared policy locks permit independent owners to progress. No global credit_lock is taken.
            var policy=db.queryForMap("SELECT epoch,protocol FROM p0_credit_writer_policy WHERE id=1"+(mysql?" FOR SHARE":""));
            if(((Number)policy.get("protocol")).intValue()!=3)throw new IllegalStateException("Unsupported credit writer protocol");
            if(!"ACTIVE".equals(db.queryForObject("SELECT status FROM p0_credit_cutover_state WHERE id=1",String.class)))
                throw new IllegalStateException("LEGACY_CUTOVER_NOT_ACTIVE");
            Object previous=mysql?db.queryForObject("SELECT @qf_credit_writer_epoch",String.class):null;
            if(mysql)db.update("SET @qf_credit_writer_epoch=?",policy.get("epoch"));
            try{delegate.lockedBalance(owner);action.run();}finally{if(mysql)db.update("SET @qf_credit_writer_epoch=?",previous);}
        });
    }
    public void credit(String owner,long amount,String reference){write(owner,()->{
        var mutation=delegate.creditTracked(owner,amount,reference);
        if(mutation.inserted()){
            db.update("INSERT INTO p0_credit_batch(reference,owner_id,source_type,source_id,original,consumed,adjusted,held,created_at) VALUES(?,?,?,?,?,0,0,0,CURRENT_TIMESTAMP)",reference,owner,reference.startsWith("payment:")?"PAYMENT":reference.startsWith("admin")?"MANUAL":"GIFT",reference,amount);
            refreshHold(reference);
        }
        else if(db.queryForList("SELECT reference FROM p0_credit_batch WHERE reference=? FOR UPDATE",reference).isEmpty()
            &&!CreditHistoricalCutover.isHistoricalCreditReplay(db,owner,amount,reference))throw new IllegalStateException("LEGACY_ATTRIBUTION_REQUIRED");
    });}
    public void debit(String owner,long amount,String reference){write(owner,()->{
        long book=delegate.lockedBalance(owner);
        // Insert-first is the replay guard; a rejected allocation rolls this and the cache back.
        if(!delegate.debitTracked(owner,amount,reference).inserted())return;
        var batches=db.queryForList("SELECT * FROM p0_credit_batch WHERE owner_id=? ORDER BY created_at,reference FOR UPDATE",owner);
        long held=0;for(var batch:batches)held=Math.addExact(held,((Number)batch.get("held")).longValue());
        if(book<0||book-held<amount)throw new DomainException("INSUFFICIENT_CREDITS","积分不足或权益被冻结");
        long remaining=amount;
        for(var batch:batches){
            if(!"ACTIVE".equals(batch.get("status")))continue;
            if(((Number)batch.get("held")).longValue()>0)continue;
            long free=((Number)batch.get("original")).longValue()-((Number)batch.get("consumed")).longValue()-((Number)batch.get("adjusted")).longValue();
            long take=Math.min(remaining,Math.max(0,free));if(take==0)continue;
            db.update("UPDATE p0_credit_batch SET consumed=consumed+? WHERE reference=?",take,batch.get("reference"));
            db.update("INSERT INTO p0_credit_allocation(debit_reference,batch_reference,owner_id,amount,restored) VALUES(?,?,?,?,0)",reference,batch.get("reference"),owner,take);
            remaining-=take;if(remaining==0)break;
        }
        if(remaining!=0)throw new IllegalStateException("LEGACY_ATTRIBUTION_REQUIRED");
    });}
    public void refund(String owner,String reference){write(owner,()->{
        var mutation=delegate.refundTracked(owner,reference);
        if(!mutation.inserted())return;
        var allocations=db.queryForList("SELECT * FROM p0_credit_allocation WHERE debit_reference=? AND owner_id=? FOR UPDATE",reference,owner);
        if(allocations.isEmpty()){
            var rows=db.queryForList("SELECT * FROM p0_legacy_debit_mapping WHERE reference=? AND owner_id=? FOR UPDATE",reference,owner);
            if(rows.size()!=1)throw new IllegalStateException("LEGACY_ATTRIBUTION_REQUIRED");
            var original=rows.get(0);if(((Number)original.get("restored")).intValue()!=0)throw new IllegalStateException("inconsistent legacy refund mapping");
            long amount=((Number)original.get("amount")).longValue();if(amount!=mutation.amount())throw new IllegalStateException("inconsistent legacy refund amount");
            db.update("INSERT INTO p0_credit_batch(reference,owner_id,source_type,source_id,original,consumed,adjusted,held,created_at) VALUES(?,?,'LEGACY_RESTORE',?,?,0,0,0,CURRENT_TIMESTAMP)","legacy-restore:"+UUID.randomUUID(),owner,reference,amount);
            db.update("UPDATE p0_legacy_debit_mapping SET restored=1 WHERE reference=?",reference);
            return;
        }
        long allocated=0;for(var allocation:allocations){
            if(((Number)allocation.get("restored")).intValue()!=0)throw new IllegalStateException("inconsistent restored allocation");
            allocated=Math.addExact(allocated,((Number)allocation.get("amount")).longValue());
        }
        if(allocated!=mutation.amount())throw new IllegalStateException("inconsistent refund allocations");
        for(var a:allocations){
            db.update("UPDATE p0_credit_batch SET consumed=consumed-? WHERE reference=?",a.get("amount"),a.get("batch_reference"));
            db.update("UPDATE p0_credit_allocation SET restored=1 WHERE debit_reference=? AND batch_reference=?",reference,a.get("batch_reference"));
            refreshHold((String)a.get("batch_reference"));
        }
    });}
    public long balance(String owner){return delegate.balance(owner);}
    private Map<String,Object> order(String owner,String id,String capture,String currency){
        var row=db.queryForMap("SELECT * FROM payment_order WHERE id=? AND creator_id=? FOR UPDATE",id,owner);
        if(!Objects.equals(capture,row.get("provider_capture_id"))||!Objects.equals(currency,row.get("currency"))||!"PAID".equals(row.get("status")))throw new IllegalStateException("financial order identity mismatch");
        if(db.queryForList("SELECT reference FROM p0_credit_batch WHERE reference=? AND owner_id=? FOR UPDATE","payment:"+id,owner).isEmpty())throw new IllegalStateException("LEGACY_ATTRIBUTION_REQUIRED");
        return row;
    }
    private void merchant(String id,String provider,String merchant,String environment){
        var b=db.queryForMap("SELECT provider,merchant,environment FROM p0_credit_payment_binding WHERE order_id=? FOR UPDATE",id);
        if(!provider.equals(b.get("provider"))||!merchant.equals(b.get("merchant"))||!environment.equals(b.get("environment")))throw new IllegalStateException("payment merchant/environment conflict");
    }
    public void bindPayment(String provider,String merchant,String environment,String owner,String id,String capture,String currency){write(owner,()->{
        var o=order(owner,id,capture,currency);
        if(!Objects.equals(o.get("provider"),provider+"_"+environment.toUpperCase(Locale.ROOT)))throw new IllegalStateException("payment provider mismatch");
        try{db.update("INSERT INTO p0_credit_payment_binding VALUES(?,?,?,?,?,?)",id,provider,merchant,environment,owner,capture);}
        catch(org.springframework.dao.DuplicateKeyException duplicate){
            var prior=db.queryForList("SELECT * FROM p0_credit_payment_binding WHERE order_id=? FOR UPDATE",id);
            if(prior.size()!=1||!owner.equals(prior.get(0).get("owner_id"))||!capture.equals(prior.get(0).get("capture_id")))
                throw new IllegalStateException("global payment identity conflict");
            merchant(id,provider,merchant,environment);
        }
    });}
    /** A capture-level reversal signal is not a native dispute or a proved monetary loss.
     * Preserve an audited order-level review hold without inventing either identity.
     * Manual risk-case RELEASE cannot clear this batch state. */
    public void holdPaymentForReview(String provider,String merchant,String environment,String owner,String id,String capture,String currency,String evidence){write(owner,()->{
        if(evidence==null||evidence.isBlank()||evidence.length()>128)throw new IllegalArgumentException("verified capture evidence required");
        order(owner,id,capture,currency);merchant(id,provider,merchant,environment);
        int changed=db.update("UPDATE p0_credit_batch SET status='REVIEW' WHERE reference=? AND owner_id=? AND status<>'REVIEW'","payment:"+id,owner);
        if(changed==1)db.update("INSERT INTO p0_credit_financial_audit(order_id,fact,delta,created_at) VALUES(?,?,0,CURRENT_TIMESTAMP)",id,"CAPTURE_REVIEW");
        refreshHold("payment:"+id);
    });}
    /** Called only after complete authenticated PayPal reporting proof. The caller must snapshot risk
     * event identities BEFORE its network reads, then auditably resolve those exact cases in this same
     * owner transaction. Neither an administrator's RELEASE alone nor a stale provider proof suffices. */
    public void releasePaymentReview(String provider,String merchant,String environment,String owner,String id,String capture,
            String currency,long expectedRefunds,long expectedLosses,Map<String,String> expectedRiskEvents,String evidenceSha256){
        if(!"PAYPAL".equals(provider)||expectedRefunds<0||expectedLosses<0||expectedRiskEvents==null
                ||expectedRiskEvents.size()>100||evidenceSha256==null||!evidenceSha256.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("complete scoped reporting proof required");
        var expected=Map.copyOf(expectedRiskEvents);
        write(owner,()->{
            var o=order(owner,id,capture,currency);merchant(id,provider,merchant,environment);
            var cases=db.queryForList("SELECT id,last_event_id,status,decision FROM payment_risk_case WHERE owner_id=? AND order_id=? AND provider=? ORDER BY id FOR UPDATE",owner,id,provider+"_"+environment.toUpperCase(Locale.ROOT));
            var actual=new TreeMap<String,String>();
            for(var row:cases){
                actual.put((String)row.get("id"),(String)row.get("last_event_id"));
                if(!"CLOSED".equals(row.get("status"))||!"RELEASE".equals(row.get("decision")))
                    throw new IllegalStateException("PAYMENT_REVIEW_CASE_NOT_RESOLVED");
            }
            if(!actual.equals(expected))throw new IllegalStateException("PAYMENT_REVIEW_EVENT_CHANGED");
            for(var dispute:currentDisputes(id))
                if(Set.of("OPEN","UNDER_REVIEW","UNKNOWN").contains(dispute.get("state"))
                    ||"LOST".equals(dispute.get("state"))&&dispute.get("loss_minor")==null)
                    throw new IllegalStateException("PAYMENT_REVIEW_DISPUTE_UNRESOLVED");
            long refunds=effectiveRefunds(id),losses=currentLosses(id),paid=minor(o.get("amount"));
            if(refunds!=expectedRefunds||losses!=expectedLosses||refunds>paid||losses>paid)
                throw new IllegalStateException("PAYMENT_REVIEW_FINANCIAL_FACT_CHANGED");
            String batch="payment:"+id;
            String state=db.queryForObject("SELECT status FROM p0_credit_batch WHERE reference=? AND owner_id=? FOR UPDATE",String.class,batch,owner);
            if(!Set.of("ACTIVE","REVIEW").contains(state))throw new IllegalStateException("PAYMENT_REVIEW_BATCH_NOT_RELEASABLE");
            if("REVIEW".equals(state)){
                if(expected.isEmpty())throw new IllegalStateException("PAYMENT_REVIEW_CASE_REQUIRED");
                db.update("UPDATE p0_credit_batch SET status='ACTIVE' WHERE reference=? AND owner_id=? AND status='REVIEW'",batch,owner);
                db.update("INSERT INTO p0_credit_financial_audit(order_id,fact,delta,created_at) VALUES(?,?,0,CURRENT_TIMESTAMP)",id,"REVIEW_RELEASE:"+evidenceSha256);
            }
            refreshHold(batch);
        });
    }
    /** Reporting may arrive after the same authoritative dispute version was recorded
     * as LOST/null. Only an already persisted PAYPAL native principal proof may fill
     * that missing amount; the generic dispute/version contract remains unchanged. */
    public void settlePayPalReportedLoss(String merchant,String environment,String dispute,String owner,String id,String capture,
            long disputed,String currency,long version,long loss,String evidenceSha256){
        if(loss<=0||version<=0||evidenceSha256==null||!evidenceSha256.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("verified PayPal principal required");
        write(owner,()->{
            var order=order(owner,id,capture,currency);merchant(id,"PAYPAL",merchant,environment);
            var proofs=db.queryForList("SELECT * FROM p0_stripe_dispute_proof WHERE provider='PAYPAL' AND merchant=? AND environment=? AND dispute_id=? FOR UPDATE",merchant,environment,dispute);
            if(proofs.size()!=1)throw new IllegalStateException("PAYPAL_NATIVE_LOSS_PROOF_REQUIRED");
            var proof=proofs.get(0);
            if(!owner.equals(proof.get("owner_id"))||!id.equals(proof.get("order_id"))||!capture.equals(proof.get("capture_id"))
                    ||!capture.equals(proof.get("charge_balance_id"))||!currency.equals(proof.get("settlement_currency"))
                    ||loss!=((Number)proof.get("loss_minor")).longValue()||minor(order.get("amount"))!=((Number)proof.get("settlement_principal_minor")).longValue()
                    ||!evidenceSha256.equals(proof.get("evidence_sha256"))||!(proof.get("withdrawal_id") instanceof String nativeId)||!nativeId.matches("[A-Za-z0-9]{1,32}"))
                throw new IllegalStateException("PAYPAL_NATIVE_LOSS_PROOF_SCOPE_CONFLICT");
            if(effectiveRefunds(id)!=0||currentDisputes(id).stream().anyMatch(row->!dispute.equals(row.get("external_id"))))
                throw new IllegalStateException("PAYPAL_OVERLAP_REQUIRES_VERIFIED_RESOLUTION");
            var rows=db.queryForList("SELECT * FROM p0_credit_dispute WHERE provider='PAYPAL' AND merchant=? AND environment=? AND external_id=? FOR UPDATE",merchant,environment,dispute);
            if(!rows.isEmpty()){
                var row=rows.get(0);long priorVersion=((Number)row.get("version")).longValue();
                if(version<priorVersion)throw new IllegalStateException("PAYPAL_NATIVE_LOSS_PROOF_STALE_VERSION");
                if(version==priorVersion&&"LOST".equals(row.get("state"))&&row.get("loss_minor")==null){
                    if(!owner.equals(row.get("owner_id"))||!id.equals(row.get("order_id"))||!capture.equals(row.get("capture_id"))
                            ||!currency.equals(row.get("currency"))||disputed!=((Number)row.get("amount_minor")).longValue()
                            ||loss>disputed||disputed>minor(order.get("amount")))throw new IllegalStateException("global dispute identity conflict");
                    nativeIdentity.requireResource("PAYPAL",merchant,environment,"DISPUTE_BINDING",dispute,owner,id,capture,currency);
                    db.update("UPDATE p0_credit_dispute SET loss_minor=? WHERE provider='PAYPAL' AND merchant=? AND environment=? AND external_id=? AND version=? AND state='LOST' AND loss_minor IS NULL",loss,merchant,environment,dispute,version);
                    adjust(owner,id,target(((Number)order.get("credits")).longValue(),loss,minor(order.get("amount"))),dispute);return;
                }
            }
            paymentDispute("PAYPAL",merchant,environment,dispute,owner,id,capture,disputed,currency,version,"LOST",loss);
        });
    }
    private static long minor(Object amount){return new BigDecimal(amount.toString()).movePointRight(2).longValueExact();}
    private static long target(long credits,long lost,long paid){return BigInteger.valueOf(credits).multiply(BigInteger.valueOf(lost)).add(BigInteger.valueOf(paid-1)).divide(BigInteger.valueOf(paid)).longValueExact();}
    private void adjust(String owner,String id,long newTarget,String fact){
        adjust(owner,id,newTarget,fact,false);
    }
    private void adjust(String owner,String id,long newTarget,String fact,boolean verifiedReturn){
        adjust(owner,id,newTarget,fact,verifiedReturn,verifiedReturn?"PAYMENT_RETURN":"PAYMENT_ADJUST");
    }
    private void adjust(String owner,String id,long newTarget,String fact,boolean verifiedReturn,String entryType){
        String batch="payment:"+id;long old=db.queryForObject("SELECT adjusted FROM p0_credit_batch WHERE reference=? FOR UPDATE",Long.class,batch);
        long heldBefore=db.queryForObject("SELECT held FROM p0_credit_batch WHERE reference=? FOR UPDATE",Long.class,batch);
        if(newTarget<old&&!verifiedReturn)throw new IllegalStateException("FUNDS_RETURN_REQUIRES_VERIFIED_COMPENSATION");
        long delta=newTarget-old;
        if(delta!=0){
            delegate.appendFinancial(owner,Math.negateExact(delta),"financial:"+UUID.randomUUID(),entryType);
            db.update("UPDATE p0_credit_batch SET adjusted=?,held=LEAST(held,GREATEST(0,original-consumed-?)) WHERE reference=?",newTarget,newTarget,batch);
            long heldAfter=db.queryForObject("SELECT held FROM p0_credit_batch WHERE reference=? FOR UPDATE",Long.class,batch);
            if(heldAfter!=heldBefore)db.update("INSERT INTO p0_credit_financial_audit(order_id,fact,delta,created_at) VALUES(?,?,?,CURRENT_TIMESTAMP)",id,"HOLD_REDUCED_BY_ADJUSTMENT",heldAfter-heldBefore);
            db.update("INSERT INTO p0_credit_financial_audit(order_id,fact,delta,created_at) VALUES(?,?,?,CURRENT_TIMESTAMP)",id,fact,-delta);
        }
        refreshHold(batch);
    }
    private void refreshHold(String batch){
        if(!batch.startsWith("payment:"))return;String id=batch.substring(8);
        int unresolved="REVIEW".equals(db.queryForObject("SELECT status FROM p0_credit_batch WHERE reference=? FOR UPDATE",String.class,batch))?1:0;
        for(var dispute:currentDisputes(id))
            if(Set.of("OPEN","UNDER_REVIEW","UNKNOWN").contains(dispute.get("state"))||"LOST".equals(dispute.get("state"))&&dispute.get("loss_minor")==null)unresolved++;
        long old=db.queryForObject("SELECT held FROM p0_credit_batch WHERE reference=? FOR UPDATE",Long.class,batch);
        db.update("UPDATE p0_credit_batch SET held=CASE WHEN ?>0 THEN GREATEST(0,original-consumed-adjusted) ELSE 0 END WHERE reference=?",unresolved,batch);
        long now=db.queryForObject("SELECT held FROM p0_credit_batch WHERE reference=? FOR UPDATE",Long.class,batch);
        if(old!=now)db.update("INSERT INTO p0_credit_financial_audit(order_id,fact,delta,created_at) VALUES(?,?,?,CURRENT_TIMESTAMP)",id,now>old?"FREEZE":"UNFREEZE",now-old);
    }
    /** Successful refund facts are immutable; a verified return only reduces their effective total. */
    private long effectiveRefunds(String id){
        var success=db.queryForList("SELECT * FROM p0_credit_refund WHERE order_id=? FOR UPDATE",id);
        var returned=db.queryForList("SELECT * FROM p0_credit_refund_return WHERE order_id=? FOR UPDATE",id);
        long amount=0;
        for(var refund:success){
            long effective=((Number)refund.get("amount_minor")).longValue();
            for(var funds:returned)if(Objects.equals(refund.get("provider"),funds.get("provider"))&&Objects.equals(refund.get("merchant"),funds.get("merchant"))
                    &&Objects.equals(refund.get("environment"),funds.get("environment"))&&Objects.equals(refund.get("external_id"),funds.get("refund_id"))){
                refundIdentity(funds,(String)refund.get("owner_id"),id,(String)refund.get("capture_id"),effective,(String)refund.get("currency"));
                effective=Math.subtractExact(effective,((Number)funds.get("amount_minor")).longValue());
            }
            amount=Math.addExact(amount,effective);
        }
        if(amount<0)throw new IllegalStateException("invalid effective refund total");
        return amount;
    }
    private List<Map<String,Object>> currentDisputes(String id){return db.queryForList("SELECT * FROM p0_credit_dispute WHERE order_id=? FOR UPDATE",id);}
    private long currentLosses(String id){
        long losses=0;for(var dispute:currentDisputes(id))if("LOST".equals(dispute.get("state"))&&dispute.get("loss_minor") instanceof Number amount)
            losses=Math.addExact(losses,amount.longValue());
        return losses;
    }
    private void refundIdentity(Map<String,Object> row,String owner,String id,String capture,long amount,String currency){
        if(!owner.equals(row.get("owner_id"))||!id.equals(row.get("order_id"))||!capture.equals(row.get("capture_id"))||amount!=((Number)row.get("amount_minor")).longValue()||!currency.equals(row.get("currency")))
            throw new IllegalStateException("global refund identity conflict");
    }
    private List<Map<String,Object>> failedRefund(String provider,String merchant,String environment,String refund){
        return db.queryForList("SELECT * FROM p0_credit_refund_terminal WHERE provider=? AND merchant=? AND environment=? AND refund_id=? FOR UPDATE",provider,merchant,environment,refund);
    }
    private void terminalEvidence(String status,String evidence){
        if(!Set.of("failed","canceled").contains(status)||evidence==null||evidence.isBlank()||evidence.length()>512)
            throw new IllegalArgumentException("authoritative failed-refund evidence required");
    }
    private void recordTerminal(String provider,String merchant,String environment,String refund,String owner,String id,String capture,long amount,String currency,String status,String evidence){
        try{db.update("INSERT INTO p0_credit_refund_terminal(provider,merchant,environment,refund_id,owner_id,order_id,capture_id,amount_minor,currency,terminal_status,evidence_id,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",provider,merchant,environment,refund,owner,id,capture,amount,currency,status,evidence);}
        catch(org.springframework.dao.DuplicateKeyException duplicate){
            // The unique insert, not an empty-range lock, arbitrates terminal identity.
            var prior=failedRefund(provider,merchant,environment,refund);
            if(prior.size()!=1)throw new IllegalStateException("refund terminal identity conflict",duplicate);
            refundIdentity(prior.get(0),owner,id,capture,amount,currency);return;
        }
        db.update("INSERT INTO p0_credit_financial_audit(order_id,fact,delta,created_at) VALUES(?,?,0,CURRENT_TIMESTAMP)",id,"REFUND_TERMINAL_RECORDED");
    }
    /** Records a verified failure which never reduced local rights. Existing successful facts need funds-return proof. */
    public void recordFailedRefund(String provider,String merchant,String environment,String refund,String owner,String id,String capture,long amount,String currency,String status,String verifiedEvidence){write(owner,()->{
        terminalEvidence(status,verifiedEvidence);
        var o=order(owner,id,capture,currency);merchant(id,provider,merchant,environment);
        if(amount<=0||amount>minor(o.get("amount")))throw new IllegalArgumentException("invalid refund amount");
        nativeIdentity.bindResource(provider,merchant,environment,"REFUND_BINDING",refund,owner,id,capture,amount,currency);
        // Establish the real terminal fact before reading another fact table. If a
        // prior success lacks compensation proof the enclosing transaction rolls it back.
        recordTerminal(provider,merchant,environment,refund,owner,id,capture,amount,currency,status,verifiedEvidence);
        var success=db.queryForList("SELECT * FROM p0_credit_refund WHERE provider=? AND merchant=? AND environment=? AND external_id=? FOR UPDATE",provider,merchant,environment,refund);
        if(!success.isEmpty()){
            refundIdentity(success.get(0),owner,id,capture,amount,currency);
            var returned=db.queryForList("SELECT * FROM p0_credit_refund_return WHERE provider=? AND merchant=? AND environment=? AND refund_id=? FOR UPDATE",provider,merchant,environment,refund);
            if(returned.isEmpty())throw new IllegalStateException("REFUND_RETURN_REQUIRES_VERIFIED_COMPENSATION");
            refundIdentity(returned.get(0),owner,id,capture,amount,currency);
        }
    });}
    /** The adapter supplies authoritative complete-return proof, normalized to the original refund currency without FX arithmetic. */
    public void compensateRefundReturnedFunds(String provider,String merchant,String environment,String returnedId,String refund,String owner,String id,String capture,long returned,String currency,String status,String verifiedEvidence){write(owner,()->{
        terminalEvidence(status,verifiedEvidence);
        var o=order(owner,id,capture,currency);merchant(id,provider,merchant,environment);long paid=minor(o.get("amount"));
        if(returnedId==null||returnedId.isBlank()||returnedId.length()>128||returned<=0||returned>paid)throw new IllegalArgumentException("invalid refund return");
        nativeIdentity.bindResource(provider,merchant,environment,"REFUND_BINDING",refund,owner,id,capture,returned,currency);
        nativeIdentity.claim(provider,merchant,environment,returnedId,owner,id,capture,"REFUND_RETURN",refund,returned,currency);
        recordTerminal(provider,merchant,environment,refund,owner,id,capture,returned,currency,status,verifiedEvidence);
        if(db.queryForObject("SELECT COUNT(*) FROM p0_credit_return WHERE provider=? AND merchant=? AND environment=? AND external_id=?",Long.class,provider,merchant,environment,returnedId)>0)
            throw new IllegalStateException("returned funds identity already belongs to a dispute");
        try{db.update("INSERT INTO p0_credit_refund_return(provider,merchant,environment,external_id,owner_id,order_id,refund_id,capture_id,amount_minor,currency,evidence_id,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",provider,merchant,environment,returnedId,owner,id,refund,capture,returned,currency,verifiedEvidence);}
        catch(org.springframework.dao.DuplicateKeyException duplicate){
            var perRefund=db.queryForList("SELECT * FROM p0_credit_refund_return WHERE provider=? AND merchant=? AND environment=? AND refund_id=? FOR UPDATE",provider,merchant,environment,refund);
            if(perRefund.size()!=1)throw new IllegalStateException("refund returned funds identity conflict",duplicate);
            var row=perRefund.get(0);refundIdentity(row,owner,id,capture,returned,currency);
            if(!returnedId.equals(row.get("external_id")))throw new IllegalStateException("refund already returned under another funds identity");
            return;
        }
        var success=db.queryForList("SELECT * FROM p0_credit_refund WHERE provider=? AND merchant=? AND environment=? AND external_id=? FOR UPDATE",provider,merchant,environment,refund);
        if(!success.isEmpty())refundIdentity(success.get(0),owner,id,capture,returned,currency);
        db.update("INSERT INTO p0_credit_financial_audit(order_id,fact,delta,created_at) VALUES(?,?,0,CURRENT_TIMESTAMP)",id,"REFUND_FUNDS_RETURN_CONFIRMED");
        // A return observed before local success must not mint rights or subtract absent success facts.
        if(success.isEmpty())return;
        long refunded=effectiveRefunds(id);
        long losses=currentLosses(id);
        if(refunded>paid||losses>paid)throw new IllegalStateException("invalid effective financial loss");
        if(refunded>0&&losses>0&&losses!=paid)throw new IllegalStateException("OVERLAP_REQUIRES_VERIFIED_RESOLUTION");
        adjust(owner,id,target(((Number)o.get("credits")).longValue(),losses>0?losses:refunded,paid),"REFUND_FUNDS_RETURN",true,"REFUND_RETURN");
    });}
    public void paymentRefund(String provider,String merchant,String environment,String refund,String owner,String id,String capture,long amount,String currency){write(owner,()->{
        var o=order(owner,id,capture,currency);merchant(id,provider,merchant,environment);long paid=minor(o.get("amount"));if(amount<=0||amount>paid)throw new IllegalArgumentException("invalid refund amount");
        nativeIdentity.bindResource(provider,merchant,environment,"REFUND_BINDING",refund,owner,id,capture,amount,currency);
        var terminal=failedRefund(provider,merchant,environment,refund);
        if(!terminal.isEmpty()){refundIdentity(terminal.get(0),owner,id,capture,amount,currency);return;}
        try{db.update("INSERT INTO p0_credit_refund VALUES(?,?,?,?,?,?,?,?,?)",provider,merchant,environment,refund,owner,id,capture,amount,currency);}
        catch(org.springframework.dao.DuplicateKeyException duplicate){
            var prior=db.queryForList("SELECT * FROM p0_credit_refund WHERE provider=? AND merchant=? AND environment=? AND external_id=? FOR UPDATE",provider,merchant,environment,refund);
            if(prior.size()!=1)throw new IllegalStateException("global refund identity conflict",duplicate);
            refundIdentity(prior.get(0),owner,id,capture,amount,currency);return;
        }
        long refunded=effectiveRefunds(id);
        long losses=currentLosses(id);
        if(refunded>paid)throw new IllegalStateException("over refund");
        if(losses>0&&losses!=paid)throw new IllegalStateException("OVERLAP_REQUIRES_VERIFIED_RESOLUTION");
        adjust(owner,id,target(((Number)o.get("credits")).longValue(),losses==paid?paid:refunded,paid),refund);
    });}
    public void paymentDispute(String provider,String merchant,String environment,String dispute,String owner,String id,String capture,long disputed,String currency,long version,String state,Long loss){write(owner,()->{
        var o=order(owner,id,capture,currency);merchant(id,provider,merchant,environment);long paid=minor(o.get("amount"));
        if(disputed<=0||disputed>paid||version<=0||!Set.of("OPEN","UNDER_REVIEW","UNKNOWN","WON","LOST").contains(state))throw new IllegalArgumentException("invalid dispute");
        if(loss!=null&&(!"LOST".equals(state)||loss<0||loss>disputed))throw new IllegalArgumentException("final verified loss required");
        nativeIdentity.bindResource(provider,merchant,environment,"DISPUTE_BINDING",dispute,owner,id,capture,disputed,currency);
        boolean inserted;
        try{
            db.update("INSERT INTO p0_credit_dispute VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",provider,merchant,environment,dispute,owner,id,capture,disputed,currency,version,state,loss);
            inserted=true;
        }catch(org.springframework.dao.DuplicateKeyException duplicate){inserted=false;}
        var rows=db.queryForList("SELECT * FROM p0_credit_dispute WHERE provider=? AND merchant=? AND environment=? AND external_id=? FOR UPDATE",provider,merchant,environment,dispute);
        if(rows.size()!=1)throw new IllegalStateException("global dispute identity conflict");
        long refunded=effectiveRefunds(id);
        long otherLosses=currentDisputes(id).stream().filter(value->!dispute.equals(value.get("external_id"))
            &&value.get("loss_minor") instanceof Number number&&number.longValue()>0).count();
        boolean overlap=loss!=null&&((refunded>0&&loss>0&&loss!=paid)||otherLosses+(loss>0?1:0)>1);
        // A verified gross loss is not yet a deduplicated net loss. Keep that observation
        // in the append-only audit, while UNKNOWN/null prevents currentLosses from
        // treating it as an already-applied permanent adjustment.
        String acceptedState=overlap?"UNKNOWN":state;
        Long acceptedLoss=overlap?null:loss;
        if(!inserted){
            var r=rows.get(0);
            if(!owner.equals(r.get("owner_id"))||!id.equals(r.get("order_id"))||!capture.equals(r.get("capture_id"))||disputed!=((Number)r.get("amount_minor")).longValue()||!currency.equals(r.get("currency")))throw new IllegalStateException("global dispute identity conflict");
            // A final accepted win is protected even when no local loss/return ever occurred.
            // Retrieval completion time is not a provider version: an older LOST query can arrive later.
            if("WON".equals(r.get("state"))&&!"WON".equals(state))
                throw new IllegalStateException("WON_DISPUTE_REQUIRES_MANUAL_REVIEW");
            // A committed funds-return fact survives any later or stale provider snapshot.
            // Check before version handling: even a newer non-WON snapshot needs manual review.
            if(!"WON".equals(state)&&db.queryForObject("SELECT COUNT(*) FROM p0_credit_return WHERE provider=? AND merchant=? AND environment=? AND dispute_id=?",Long.class,provider,merchant,environment,dispute)>0)
                throw new IllegalStateException("RETURNED_DISPUTE_REQUIRES_MANUAL_REVIEW");
            long oldVersion=((Number)r.get("version")).longValue();if(version<oldVersion)return;
            if(overlap&&r.get("loss_minor")!=null){
                // Never erase a previously committed loss in order to establish a hold.
                // An ambiguous later observation needs explicit review before release.
                if(version==oldVersion&&(!state.equals(r.get("state"))||!Objects.equals(loss,r.get("loss_minor"))))
                    throw new IllegalStateException("conflicting dispute version");
                recordDisputeOverlap(id,dispute,version,loss);
                db.update("UPDATE p0_credit_batch SET status='REVIEW' WHERE reference=?","payment:"+id);
                refreshHold("payment:"+id);return;
            }
            if(version==oldVersion){
                if(acceptedState.equals(r.get("state"))&&Objects.equals(acceptedLoss,r.get("loss_minor"))){
                    if(overlap&&!hasDisputeOverlap(id,dispute,version,loss))throw new IllegalStateException("conflicting dispute version");
                    return;
                }
                // If exact refund-return facts removed the overlap, the same authority
                // snapshot can be re-evaluated. A generic UNKNOWN is not such proof.
                if(!("UNKNOWN".equals(r.get("state"))&&r.get("loss_minor")==null&&!overlap&&"LOST".equals(state)
                    &&loss!=null&&hasDisputeOverlap(id,dispute,version,loss)))throw new IllegalStateException("conflicting dispute version");
            }
            if(r.get("loss_minor")!=null&&!"LOST".equals(state))throw new IllegalStateException("FUNDS_RETURN_REQUIRES_VERIFIED_COMPENSATION");
        }
        db.update("UPDATE p0_credit_dispute SET state=?,version=?,loss_minor=? WHERE provider=? AND merchant=? AND environment=? AND external_id=?",acceptedState,version,acceptedLoss,provider,merchant,environment,dispute);
        if(overlap){recordDisputeOverlap(id,dispute,version,loss);refreshHold("payment:"+id);}
        else if(loss!=null){
            adjust(owner,id,target(((Number)o.get("credits")).longValue(),loss==0?refunded:loss,paid),dispute);
        }else refreshHold("payment:"+id);
    });}
    private static String disputeOverlapFact(String dispute,long version,long loss){
        try{return "LOST_OVERLAP:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(dispute.getBytes(StandardCharsets.UTF_8)))+":"+version+":"+loss;}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private boolean hasDisputeOverlap(String id,String dispute,long version,long loss){
        return db.queryForObject("SELECT COUNT(*) FROM p0_credit_financial_audit WHERE order_id=? AND fact=?",Long.class,id,disputeOverlapFact(dispute,version,loss))>0;
    }
    private void recordDisputeOverlap(String id,String dispute,long version,long loss){
        if(!hasDisputeOverlap(id,dispute,version,loss))db.update("INSERT INTO p0_credit_financial_audit(order_id,fact,delta,created_at) VALUES(?,?,0,CURRENT_TIMESTAMP)",id,disputeOverlapFact(dispute,version,loss));
    }
    public void compensateReturnedFunds(String provider,String merchant,String environment,String returnedId,String dispute,String owner,String id,String capture,long returned,String currency,String verifiedEvidence){write(owner,()->{
        var o=order(owner,id,capture,currency);merchant(id,provider,merchant,environment);
        if(verifiedEvidence==null||verifiedEvidence.isBlank())throw new IllegalStateException("authoritative returned-funds evidence required");
        nativeIdentity.requireResource(provider,merchant,environment,"DISPUTE_BINDING",dispute,owner,id,capture,currency);
        nativeIdentity.claim(provider,merchant,environment,returnedId,owner,id,capture,"DISPUTE_RETURN",dispute,returned,currency);
        if(db.queryForObject("SELECT COUNT(*) FROM p0_credit_refund_return WHERE provider=? AND merchant=? AND environment=? AND external_id=?",Long.class,provider,merchant,environment,returnedId)>0)
            throw new IllegalStateException("returned funds identity already belongs to a refund");
        try{db.update("INSERT INTO p0_credit_return VALUES(?,?,?,?,?,?,?,?,?,?)",provider,merchant,environment,returnedId,owner,id,dispute,returned,currency,verifiedEvidence);}
        catch(org.springframework.dao.DuplicateKeyException duplicate){
            var prior=db.queryForList("SELECT * FROM p0_credit_return WHERE provider=? AND merchant=? AND environment=? AND external_id=? FOR UPDATE",provider,merchant,environment,returnedId);
            if(prior.size()!=1)throw new IllegalStateException("returned funds identity conflict",duplicate);
            var r=prior.get(0);if(!owner.equals(r.get("owner_id"))||!id.equals(r.get("order_id"))||!dispute.equals(r.get("dispute_id"))||returned!=((Number)r.get("amount_minor")).longValue()||!currency.equals(r.get("currency")))throw new IllegalStateException("returned funds identity conflict");return;
        }
        var c=db.queryForMap("SELECT * FROM p0_credit_dispute WHERE provider=? AND merchant=? AND environment=? AND external_id=? FOR UPDATE",provider,merchant,environment,dispute);
        if(!owner.equals(c.get("owner_id"))||!id.equals(c.get("order_id"))||!capture.equals(c.get("capture_id"))||!"LOST".equals(c.get("state"))||c.get("loss_minor")==null||returned!=((Number)c.get("loss_minor")).longValue())throw new IllegalStateException("complete confirmed case recovery required");
        if(currentDisputes(id).stream().anyMatch(value->!dispute.equals(value.get("external_id"))&&value.get("loss_minor") instanceof Number amount&&amount.longValue()>0))throw new IllegalStateException("OVERLAP_REQUIRES_VERIFIED_RESOLUTION");
        db.update("UPDATE p0_credit_dispute SET state='WON',version=version+1,loss_minor=NULL WHERE provider=? AND merchant=? AND environment=? AND external_id=?",provider,merchant,environment,dispute);
        long refunds=effectiveRefunds(id);
        adjust(owner,id,target(((Number)o.get("credits")).longValue(),refunds,minor(o.get("amount"))),returnedId,true);
    });}
}
