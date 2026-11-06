package com.qingfeng.music.server;
import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.credits.CreditsLedger;
import com.qingfeng.music.server.security.CreatorPrincipal;
import com.qingfeng.music.server.web.app.WelcomeController;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest @ActiveProfiles("test")
class WelcomeDialogIntegrationTest {
 @Autowired IdentityApi identity; @Autowired CreditsLedger credits; @Autowired WelcomeController welcome; @Autowired JdbcTemplate db;
 private IdentityApi.Session newcomer(){var g=identity.create();return identity.signInWithGoogle(g.token(),g.creatorId(),UUID.randomUUID().toString(),null,null);}
 @Test void popupRequiresActualWelcomeGrantAndDoesNotGrantMoreCredits(){
  var guest=identity.create();assertFalse(welcome.claim(new CreatorPrincipal(guest.creatorId())).getBody().show());
  credits.credit(guest.creatorId(),100,"unrelated:"+UUID.randomUUID());assertFalse(welcome.claim(new CreatorPrincipal(guest.creatorId())).getBody().show());
  var user=newcomer();credits.debit(user.creatorId(),100,"spent:"+UUID.randomUUID());
  var result=welcome.claim(new CreatorPrincipal(user.creatorId())).getBody();assertTrue(result.show());assertEquals(100,result.amount());
  assertEquals(0,credits.balance(user.creatorId()));assertFalse(welcome.claim(new CreatorPrincipal(user.creatorId())).getBody().show());
 }
 @Test void establishedAccountsDoNotReceiveANewUserPopup(){
  var old=newcomer();db.update("UPDATE credit_ledger SET created_at=? WHERE reference_id=?",Timestamp.from(Instant.now().minusSeconds(86400)),"first-login:"+old.creatorId());
  assertFalse(welcome.claim(new CreatorPrincipal(old.creatorId())).getBody().show());
 }
 @Test void twoTabsOrDevicesCanClaimThePopupOnlyOnce()throws Exception{
  var user=newcomer();var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
  Callable<Boolean> claim=()->{start.await();return welcome.claim(new CreatorPrincipal(user.creatorId())).getBody().show();};
  try{var a=pool.submit(claim);var b=pool.submit(claim);start.countDown();assertNotEquals(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS));assertEquals(100,credits.balance(user.creatorId()));}finally{pool.shutdownNow();}
  assertTrue(welcome.claim(new CreatorPrincipal(newcomer().creatorId())).getBody().show());
 }
}
