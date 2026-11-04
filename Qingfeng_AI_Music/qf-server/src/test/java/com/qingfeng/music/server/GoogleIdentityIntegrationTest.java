package com.qingfeng.music.server;

import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.work.api.WorkApi;
import com.qingfeng.music.common.exception.DomainException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test")
class GoogleIdentityIntegrationTest {
    @Autowired IdentityApi identity;
    @Autowired WorkApi works;
    private final WorkApi.Draft draft=new WorkApi.Draft("妈妈","生日祝福","一起做饭的回忆","谢谢你","民谣","回家的风");
    @Test void firstGoogleBindingKeepsAnonymousWorksAndRotatesOnlyCurrentSession(){
        var anonymous=identity.create();var work=works.create(anonymous.creatorId(),draft);String recovery=identity.issueRecoveryCode(anonymous.creatorId());
        var otherDevice=identity.recover(recovery);
        var google=identity.signInWithGoogle(anonymous.token(),anonymous.creatorId(),UUID.randomUUID().toString(),"one@example.test","用户");
        assertEquals(anonymous.creatorId(),google.creatorId());assertEquals(work.id(),works.get(google.creatorId(),work.id()).id());
        assertTrue(identity.resolve(anonymous.token()).isEmpty());assertEquals(google.creatorId(),identity.resolve(otherDevice.token()).orElseThrow());
        assertEquals("one@example.test",identity.googleIdentity(google.creatorId()).orElseThrow().email());
    }
    @Test void existingGoogleCanSignInFromEmptyDeviceButCannotSwallowAnotherCollection(){
        String sub=UUID.randomUUID().toString();var first=identity.create();var bound=identity.signInWithGoogle(first.token(),first.creatorId(),sub,"old@example.test","旧名称");
        var empty=identity.create();var restored=identity.signInWithGoogle(empty.token(),empty.creatorId(),sub,"new@example.test","新名称");
        assertEquals(bound.creatorId(),restored.creatorId());assertEquals("new@example.test",identity.googleIdentity(restored.creatorId()).orElseThrow().email());
        var withWork=identity.create();var work=works.create(withWork.creatorId(),draft);
        var error=assertThrows(DomainException.class,()->identity.signInWithGoogle(withWork.token(),withWork.creatorId(),sub,"new@example.test","新名称"));
        assertEquals("GOOGLE_ACCOUNT_CONFLICT",error.code());assertEquals(withWork.creatorId(),identity.resolve(withWork.token()).orElseThrow());
        assertEquals(work.id(),works.get(withWork.creatorId(),work.id()).id());assertTrue(identity.googleIdentity(withWork.creatorId()).isEmpty());
    }
    @Test void emailDoesNotMergeSubjectsAndSubjectCaseRemainsSignificant(){
        String base=UUID.randomUUID().toString();var a=identity.create();var b=identity.create();
        var ga=identity.signInWithGoogle(a.token(),a.creatorId(),"A"+base,"same@example.test",null);
        var gb=identity.signInWithGoogle(b.token(),b.creatorId(),"a"+base,"same@example.test",null);
        assertNotEquals(ga.creatorId(),gb.creatorId());
        assertThrows(DomainException.class,()->identity.signInWithGoogle(ga.token(),ga.creatorId(),UUID.randomUUID().toString(),"same@example.test",null));
    }
    @Test void changedCreatorOrExpiredTokenCannotBindGoogle(){
        var a=identity.create();var b=identity.create();
        assertThrows(DomainException.class,()->identity.signInWithGoogle(a.token(),b.creatorId(),UUID.randomUUID().toString(),null,null));
        var google=identity.signInWithGoogle(a.token(),a.creatorId(),UUID.randomUUID().toString(),null,null);
        assertTrue(identity.googleIdentity(google.creatorId()).isPresent());
        assertThrows(DomainException.class,()->identity.signInWithGoogle(a.token(),a.creatorId(),UUID.randomUUID().toString(),null,null));
        assertEquals(b.creatorId(),identity.resolve(b.token()).orElseThrow());
    }
    @Test void concurrentUseOfSameAnonymousTokenOnlySucceedsOnce()throws Exception{
        var a=identity.create();String sub=UUID.randomUUID().toString();var pool=Executors.newFixedThreadPool(2);
        Callable<Boolean> bind=()->{try{identity.signInWithGoogle(a.token(),a.creatorId(),sub,null,null);return true;}catch(DomainException error){return false;}};
        try{var first=pool.submit(bind);var second=pool.submit(bind);assertNotEquals(first.get(5,TimeUnit.SECONDS),second.get(5,TimeUnit.SECONDS));}finally{pool.shutdownNow();}
    }
    @Test void logoutOnlyRevokesCurrentTokenAndKeepsGoogleWorksRecoveryAndOtherDevices(){
        var anonymous=identity.create();var work=works.create(anonymous.creatorId(),draft);
        var google=identity.signInWithGoogle(anonymous.token(),anonymous.creatorId(),UUID.randomUUID().toString(),"kept@example.test","保留账户");
        var otherDevice=identity.recover(identity.issueRecoveryCode(google.creatorId()));String recovery=identity.issueRecoveryCode(google.creatorId());
        identity.logout(google.token());identity.logout(google.token());identity.logout(null);identity.logout("invalid-token");
        assertTrue(identity.resolve(google.token()).isEmpty());assertEquals(google.creatorId(),identity.resolve(otherDevice.token()).orElseThrow());
        assertEquals("kept@example.test",identity.googleIdentity(google.creatorId()).orElseThrow().email());assertEquals(work.id(),works.get(google.creatorId(),work.id()).id());
        assertTrue(identity.hasRecoveryCode(google.creatorId()));assertEquals(google.creatorId(),identity.recover(recovery).creatorId());
    }
}
