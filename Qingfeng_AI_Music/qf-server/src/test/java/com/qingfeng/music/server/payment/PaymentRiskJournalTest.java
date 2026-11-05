package com.qingfeng.music.server.payment;

import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.server.payment.risk.PaymentRiskJournal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:payment_risk;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class PaymentRiskJournalTest {
    @Autowired PaymentRiskJournal journal;
    @Autowired IdentityApi identity;
    @Autowired JdbcTemplate db;

    @Test void verifiedEventsAreIdempotentAndConflictingReplaysAreRejected() {
        String creator = identity.create().creatorId(), order = UUID.randomUUID().toString();
        db.update("INSERT INTO payment_order(id,creator_id,package_id,amount,currency,credits,status,created_at) VALUES(?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                order,creator,"test",1,"GBP",10,"PAID");
        journal.recordVerifiedPaypalEvent(order,"WH-test","PAYMENT.CAPTURE.REFUNDED","refund-1");
        journal.recordVerifiedPaypalEvent(order,"WH-test","PAYMENT.CAPTURE.REFUNDED","refund-1");
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM payment_risk_event WHERE event_id='WH-test'",Integer.class));
        assertThrows(IllegalStateException.class,() -> journal.recordVerifiedPaypalEvent(order,"WH-test","PAYMENT.CAPTURE.REFUNDED","refund-2"));
        assertThrows(IllegalArgumentException.class,() -> journal.recordVerifiedPaypalEvent(order,"WH-2","PAYMENT.CAPTURE.COMPLETED","capture-1"));
        assertThrows(IllegalArgumentException.class,() -> journal.recordVerifiedPaypalEvent(UUID.randomUUID().toString(),"WH-3","CUSTOMER.DISPUTE.CREATED","case-1"));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM payment_risk_event",Integer.class));
    }
}
