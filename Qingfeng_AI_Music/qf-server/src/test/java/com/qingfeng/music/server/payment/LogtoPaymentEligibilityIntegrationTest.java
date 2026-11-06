package com.qingfeng.music.server.payment;

import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.server.security.LogtoIdentityService;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:logto_payment;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class LogtoPaymentEligibilityIntegrationTest {
    @Autowired IdentityApi identity;
    @Autowired LogtoIdentityService logto;
    @Autowired PaymentService payments;
    @Autowired JdbcTemplate db;

    @Test void guestCannotReserveButVerifiedLogtoAccountCan() {
        var creator=identity.create();
        String owner=creator.creatorId();
        var rejected=assertThrows(ResponseStatusException.class,
            ()->payments.reserve(owner,UUID.randomUUID().toString(),"test",new BigDecimal("1.00"),"USD",10));
        assertEquals(403,rejected.getStatusCode().value());

        logto.signIn(creator.token(),owner,"http://localhost:3001/oidc",UUID.randomUUID().toString(),null,"+8613800001234",null);
        payments.reserve(owner,UUID.randomUUID().toString(),"test",new BigDecimal("1.00"),"USD",10);
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM payment_order WHERE creator_id=?",Integer.class,owner));
    }
}
