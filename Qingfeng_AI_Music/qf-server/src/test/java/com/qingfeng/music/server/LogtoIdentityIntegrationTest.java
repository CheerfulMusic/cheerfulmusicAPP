package com.qingfeng.music.server;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.server.security.LogtoIdentityService;
import com.qingfeng.music.work.api.WorkApi;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test")
class LogtoIdentityIntegrationTest {
    @Autowired IdentityApi identity;
    @Autowired LogtoIdentityService logto;
    @Autowired WorkApi works;
    @Autowired JdbcTemplate db;
    private final String issuer="http://localhost:3001/oidc";
    private final WorkApi.Draft draft=new WorkApi.Draft("妈妈","生日祝福","一起做饭的回忆","谢谢你","民谣","回家的风");

    @Test void bindingPreservesWorksAndRotatesBrowserSession(){
        var guest=identity.create();var work=works.create(guest.creatorId(),draft);
        var signed=logto.signIn(guest.token(),guest.creatorId(),issuer,UUID.randomUUID().toString(),"user@example.test","+8613800001234","用户");
        assertEquals(guest.creatorId(),signed.creatorId());
        assertTrue(identity.resolve(guest.token()).isEmpty());
        assertEquals(work.id(),works.get(signed.creatorId(),work.id()).id());
        assertEquals("user@example.test",logto.profile(signed.creatorId()).orElseThrow().email());
        assertEquals("****1234",logto.profile(signed.creatorId()).orElseThrow().phoneMasked());
    }
    @Test void existingSubjectRestoresAccountAndAssociatesGuestWorks(){
        String subject=UUID.randomUUID().toString();var first=identity.create();
        var bound=logto.signIn(first.token(),first.creatorId(),issuer,subject,null,null,null);
        var empty=identity.create();var restored=logto.signIn(empty.token(),empty.creatorId(),issuer,subject,null,null,null);
        assertEquals(bound.creatorId(),restored.creatorId());
        var occupied=identity.create();var work=works.create(occupied.creatorId(),draft);
        var merged=logto.signIn(occupied.token(),occupied.creatorId(),issuer,subject,null,null,null);
        assertEquals(bound.creatorId(),merged.creatorId());assertTrue(identity.resolve(occupied.token()).isEmpty());
        assertEquals(work.id(),works.get(merged.creatorId(),work.id()).id());

    }
    @Test void emailNeverLinksAccountsAndOldTokenCannotBeReused(){
        var a=identity.create();var b=identity.create();String email="same@example.test";
        var first=logto.signIn(a.token(),a.creatorId(),issuer,"A"+UUID.randomUUID(),email,null,null);
        var second=logto.signIn(b.token(),b.creatorId(),issuer,"B"+UUID.randomUUID(),email,null,null);
        assertNotEquals(first.creatorId(),second.creatorId());
        assertThrows(DomainException.class,()->logto.signIn(a.token(),a.creatorId(),issuer,UUID.randomUUID().toString(),email,null,null));
    }
    @Test void pendingPaymentCannotBeLostWhenRestoringAnotherAccount(){
        var owner=identity.create();String subject=UUID.randomUUID().toString();
        logto.signIn(owner.token(),owner.creatorId(),issuer,subject,null,null,null);
        var other=identity.create();
        db.update("INSERT INTO payment_order(id,creator_id,package_id,amount,currency,credits,status,created_at) VALUES(?,?,?,?,?,?,?,?)",
            UUID.randomUUID().toString(),other.creatorId(),"test",new BigDecimal("1.00"),"USD",1,"PENDING",Timestamp.from(Instant.now()));
        assertEquals("LOGTO_ACCOUNT_CONFLICT",assertThrows(DomainException.class,
            ()->logto.signIn(other.token(),other.creatorId(),issuer,subject,null,null,null)).code());
        assertEquals(other.creatorId(),identity.resolve(other.token()).orElseThrow());
    }
    @Test void legacyGoogleLoginCannotAbandonAConnectedLogtoAccount(){
        var logtoGuest=identity.create();
        var connected=logto.signIn(logtoGuest.token(),logtoGuest.creatorId(),issuer,UUID.randomUUID().toString(),null,null,null);
        var googleGuest=identity.create();String googleSubject=UUID.randomUUID().toString();
        identity.signInWithGoogle(googleGuest.token(),googleGuest.creatorId(),googleSubject,null,null);
        assertEquals("GOOGLE_ACCOUNT_CONFLICT",assertThrows(DomainException.class,
            ()->identity.signInWithGoogle(connected.token(),connected.creatorId(),googleSubject,null,null)).code());
        assertEquals(connected.creatorId(),identity.resolve(connected.token()).orElseThrow());
    }
}
