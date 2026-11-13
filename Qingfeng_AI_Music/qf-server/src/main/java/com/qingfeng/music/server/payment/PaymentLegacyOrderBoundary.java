package com.qingfeng.music.server.payment;

import java.sql.Timestamp;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Old channel orders retain their original records; aggregate carryover is not payment provenance. */
final class PaymentLegacyOrderBoundary {
    static final class ReadOnlyOrder extends ResponseStatusException {
        ReadOnlyOrder(){super(HttpStatus.CONFLICT,"LEGACY_PAYMENT_READ_ONLY: 历史订单保留查询，资金状态需人工核对");}
    }
    private final JdbcTemplate db;
    private final boolean enabled;
    PaymentLegacyOrderBoundary(JdbcTemplate db,boolean enabled){this.db=db;this.enabled=enabled;}
    boolean enabled(){return enabled;}
    Timestamp activation(){
        if(!enabled)return null;
        try{
            var state=db.queryForMap("SELECT c.status,c.activated_at,p.protocol FROM p0_credit_cutover_state c JOIN p0_credit_writer_policy p ON p.id=c.id WHERE c.id=1");
            if(!"ACTIVE".equals(state.get("status"))||!(state.get("protocol") instanceof Number protocol)||protocol.intValue()!=3
                ||!(state.get("activated_at") instanceof Timestamp time))throw unavailable();
            return time;
        }catch(ResponseStatusException unavailable){throw unavailable;}
        catch(RuntimeException unavailable){throw unavailable();}
    }
    boolean readOnly(Map<String,Object> order){
        if(!enabled)return false;
        if(!Set.of("PAYPAL_LIVE","PAYPAL_SANDBOX","AIRWALLEX").contains(order.get("provider")))throw unavailable();
        Timestamp activation=activation();
        if(!(order.get("created_at") instanceof Timestamp created))throw unavailable();
        // Include equality: old DATETIME/TIMESTAMP columns can have only second precision.
        return !created.after(activation);
    }
    void requireMutable(Map<String,Object> order){if(readOnly(order))throw new ReadOnlyOrder();}
    private static ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"LEGACY_PAYMENT_BOUNDARY_NOT_READY");}
}
