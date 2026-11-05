package com.qingfeng.music.server.payment;

import com.qingfeng.music.credits.CreditsLedger;
import com.qingfeng.music.identity.api.IdentityApi;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:paypal_service;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","qingfeng.paypal.merchant-id=TESTMERCHANT"})
@ActiveProfiles("test")
class PayPalPaymentServiceTest {
    @Autowired PayPalPaymentService service; @Autowired IdentityApi identity; @Autowired JdbcTemplate db; @Autowired CreditsLedger ledger;
    @Test void settlementRequiresMatchingCompletedCaptureAndIsIdempotent(){
        String owner=identity.create().creatorId(), id=UUID.randomUUID().toString();
        db.update("INSERT INTO payment_order(id,creator_id,package_id,amount,currency,credits,provider_link_id,status,created_at,provider) VALUES(?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,?)",id,owner,"starter-GBP",new BigDecimal("4.99"),"GBP",100,"REMOTE123","PENDING","PAYPAL_SANDBOX");
        var wrong=new PayPalCheckoutGateway.Snapshot("REMOTE123",id,"COMPLETED","TESTMERCHANT","CAP1","COMPLETED",new BigDecimal("0.01"),"GBP");
        assertThrows(ResponseStatusException.class,()->service.settle(id,wrong)); assertEquals(0,ledger.balance(owner));
        var incomplete=new PayPalCheckoutGateway.Snapshot("REMOTE123",id,"COMPLETED","TESTMERCHANT","CAP1","PENDING",new BigDecimal("4.99"),"GBP");
        assertThrows(ResponseStatusException.class,()->service.settle(id,incomplete)); assertEquals(0,ledger.balance(owner));
        var valid=new PayPalCheckoutGateway.Snapshot("REMOTE123",id,"COMPLETED","TESTMERCHANT","CAP1","COMPLETED",new BigDecimal("4.99"),"GBP");
        service.settle(id,valid);service.settle(id,valid);assertEquals(100,ledger.balance(owner));
        assertEquals("PAID",service.order(owner,id).get("status"));
        assertThrows(ResponseStatusException.class,()->service.order(identity.create().creatorId(),id));
    }
    @Test void disabledDoesNotAcceptWebhookOrOrders(){
        assertEquals(false,service.catalog().get("enabled"));
        assertThrows(ResponseStatusException.class,()->service.webhook(null,new byte[0]));
        assertThrows(ResponseStatusException.class,()->service.create(identity.create().creatorId(),"starter-GBP"));
    }
}
