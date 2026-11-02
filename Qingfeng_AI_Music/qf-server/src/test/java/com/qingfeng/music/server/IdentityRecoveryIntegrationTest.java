package com.qingfeng.music.server;

import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.common.exception.DomainException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test")
class IdentityRecoveryIntegrationTest {
    @Autowired IdentityApi identity;
    @Test void recoveryCodeIsOneTimeAndRestoresSameCreator(){var a=identity.create();String code=identity.issueRecoveryCode(a.creatorId());assertTrue(identity.hasRecoveryCode(a.creatorId()));var restored=identity.recover(code);assertEquals(a.creatorId(),identity.resolve(restored.token()).orElseThrow());assertFalse(identity.hasRecoveryCode(a.creatorId()));assertThrows(DomainException.class,()->identity.recover(code));}
    @Test void issuingNewCodeInvalidatesOldCode(){var a=identity.create();String first=identity.issueRecoveryCode(a.creatorId()),second=identity.issueRecoveryCode(a.creatorId());assertThrows(DomainException.class,()->identity.recover(first));assertEquals(a.creatorId(),identity.recover(second).creatorId());}
    @Test void concurrentConsumptionOnlySucceedsOnce()throws Exception{var a=identity.create();String code=identity.issueRecoveryCode(a.creatorId());var pool=Executors.newFixedThreadPool(2);java.util.concurrent.Callable<Boolean> consume=()->{try{identity.recover(code);return true;}catch(DomainException e){return false;}};try{var x=pool.submit(consume);var y=pool.submit(consume);assertNotEquals(x.get(5,TimeUnit.SECONDS),y.get(5,TimeUnit.SECONDS));}finally{pool.shutdownNow();}}
}
