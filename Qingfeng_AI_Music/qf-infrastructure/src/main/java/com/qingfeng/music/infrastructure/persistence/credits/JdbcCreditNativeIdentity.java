package com.qingfeng.music.infrastructure.persistence.credits;

import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Shared insert-first native return identity. Call inside the owner's existing financial transaction. */
final class JdbcCreditNativeIdentity {
    private final JdbcTemplate db;
    JdbcCreditNativeIdentity(JdbcTemplate db){this.db=db;}
    private static String key(String kind,String resource){
        if(!Set.of("REFUND_BINDING","DISPUTE_BINDING").contains(kind)||resource==null||resource.isBlank()||resource.length()>128)
            throw new IllegalArgumentException("invalid financial resource binding");
        try{return ("REFUND_BINDING".equals(kind)?"refund:":"dispute:")+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(resource.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    void bindResource(String provider,String merchant,String environment,String kind,String resource,String owner,String order,String capture,long amount,String currency){
        try{claim(provider,merchant,environment,key(kind,resource),owner,order,capture,kind,resource,amount,currency);}
        catch(IllegalStateException conflict){throw new IllegalStateException("REFUND_BINDING".equals(kind)?"global refund identity conflict":"global dispute identity conflict",conflict);}
    }
    void requireResource(String provider,String merchant,String environment,String kind,String resource,String owner,String order,String capture,String currency){
        var rows=db.queryForList("SELECT * FROM p0_credit_native_identity WHERE provider=? AND merchant=? AND environment=? AND external_id=? FOR UPDATE",provider,merchant,environment,key(kind,resource));
        if(rows.size()!=1)throw new IllegalStateException("LEGACY_ATTRIBUTION_REQUIRED");
        var prior=rows.get(0);
        if(!owner.equals(prior.get("owner_id"))||!order.equals(prior.get("order_id"))||!capture.equals(prior.get("capture_id"))
                ||!kind.equals(prior.get("kind"))||!resource.equals(prior.get("resource_id"))||!currency.equals(prior.get("currency")))
            throw new IllegalStateException("global financial resource identity conflict");
    }
    void claim(String provider,String merchant,String environment,String nativeId,String owner,String order,
               String capture,String kind,String resource,long amount,String currency){
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("native identity requires financial transaction");
        for(String identity:List.of(provider,merchant,environment,nativeId,owner,order,capture,kind,resource,currency))
            if(identity.isBlank())throw new IllegalArgumentException("native identity binding required");
        if(!Set.of("REFUND_RETURN","DISPUTE_RETURN","REFUND_BINDING","DISPUTE_BINDING").contains(kind)||amount<=0)throw new IllegalArgumentException("invalid native return identity");
        if(kind.endsWith("RETURN")&&!nativeId.matches("[A-Za-z0-9_-]{1,128}"))throw new IllegalArgumentException("invalid native return transaction id");
        try{
            db.update("INSERT INTO p0_credit_native_identity(provider,merchant,environment,external_id,owner_id,order_id,capture_id,kind,resource_id,amount_minor,currency,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                provider,merchant,environment,nativeId,owner,order,capture,kind,resource,amount,currency);
        }catch(DuplicateKeyException duplicate){
            // Never pre-read a missing key: the shared unique insert arbitrates both return tables and owners.
            var rows=db.queryForList("SELECT * FROM p0_credit_native_identity WHERE provider=? AND merchant=? AND environment=? AND external_id=? FOR UPDATE",provider,merchant,environment,nativeId);
            if(rows.size()!=1)throw new IllegalStateException("native returned funds identity conflicts with an existing resource");
            var prior=rows.get(0);
            if(!owner.equals(prior.get("owner_id"))||!order.equals(prior.get("order_id"))||!capture.equals(prior.get("capture_id"))
                    ||!kind.equals(prior.get("kind"))||!resource.equals(prior.get("resource_id"))
                    ||amount!=((Number)prior.get("amount_minor")).longValue()||!currency.equals(prior.get("currency")))
                throw new IllegalStateException("native returned funds identity conflict");
        }
    }
}
