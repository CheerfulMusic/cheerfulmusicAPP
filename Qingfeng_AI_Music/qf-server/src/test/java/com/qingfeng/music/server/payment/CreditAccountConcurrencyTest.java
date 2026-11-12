package com.qingfeng.music.server.payment;

import com.qingfeng.music.credits.CreditsLedger;
import com.qingfeng.music.credits.FirstLoginCredits;
import com.qingfeng.music.identity.api.IdentityApi;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:credit_account_concurrency;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class CreditAccountConcurrencyTest {
    @Autowired CreditsLedger credits; @Autowired IdentityApi identity; @Autowired JdbcTemplate db;
    @Autowired PlatformTransactionManager manager;
    String owner(){return identity.create().creatorId();}
    String key(){return UUID.randomUUID().toString();}
    @Test void unrelatedAccountsProgressWhileAnotherTransactionHoldsTheOldGlobalLock() throws Exception {
        String a=owner(),b=owner();var pool=Executors.newFixedThreadPool(2);var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try{
            var first=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(t->{
                db.queryForObject("SELECT id FROM credit_lock WHERE id=1 FOR UPDATE",Integer.class);credits.credit(a,10,key());locked.countDown();await(release);
            }));
            assertTrue(locked.await(5,TimeUnit.SECONDS));
            pool.submit(()->credits.credit(b,20,key())).get(3,TimeUnit.SECONDS);
            assertEquals(20,credits.balance(b));release.countDown();first.get(5,TimeUnit.SECONDS);
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void sameAccountConcurrentReplayCreditsExactlyOnce() throws Exception {
        String owner=owner(),key=key();var pool=Executors.newFixedThreadPool(8);var start=new CountDownLatch(1);
        try{
            var futures=new java.util.ArrayList<Future<?>>();
            for(int n=0;n<16;n++)futures.add(pool.submit(()->{await(start);credits.credit(owner,50,key);}));
            start.countDown();for(var future:futures)future.get(10,TimeUnit.SECONDS);
            assertEquals(50,credits.balance(owner));
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM credit_ledger WHERE owner_id=?",Integer.class,owner));
        }finally{pool.shutdownNow();}
    }
    @Test void legacyBalanceInitializesOnceAndBothBalanceAndLedgerRollBack(){
        String owner=owner();
        db.update("INSERT INTO credit_ledger(entry_type,reference_id,owner_id,amount,created_at) VALUES('CREDIT',?,?,70,CURRENT_TIMESTAMP)",key(),owner);
        credits.debit(owner,20,key());assertEquals(50,credits.balance(owner));
        String rollback=key();
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).executeWithoutResult(t->{credits.credit(owner,30,rollback);throw new IllegalStateException();}));
        assertEquals(50,credits.balance(owner));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM credit_ledger WHERE reference_id=?",Integer.class,rollback));
    }
    @Test void aGuestWithCachedFinancialBalanceCannotMoveItsWorksOrBalance(){
        var guest=identity.create();String target=owner();credits.credit(guest.creatorId(),25,key());
        assertFalse(identity.claimGuestWorks(guest.token(),guest.creatorId(),target));
        assertEquals(25,credits.balance(guest.creatorId()));assertEquals(0,credits.balance(target));
    }
    @Test void invitationRewardAndInviterDebitCannotFormTheFormerGlobalLockCycle() throws Exception {
        String invitee=owner(),inviter=owner();credits.credit(inviter,100,key());
        var inviterLocked=new CountDownLatch(1);var welcomeCredited=new CountDownLatch(1);
        CreditsLedger tracking=new CreditsLedger(){
            public void credit(String owner,long amount,String reference){credits.credit(owner,amount,reference);if(owner.equals(invitee))welcomeCredited.countDown();}
            public void debit(String owner,long amount,String reference){credits.debit(owner,amount,reference);}
            public void refund(String owner,String reference){credits.refund(owner,reference);}
            public long balance(String owner){return credits.balance(owner);}
        };
        var grant=new FirstLoginCredits(tracking,id->credits.credit(inviter,20,"invite:"+id));
        var pool=Executors.newFixedThreadPool(2);
        try{
            var debit=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(t->{db.queryForList("SELECT id FROM creator WHERE id=? FOR UPDATE",inviter);inviterLocked.countDown();await(welcomeCredited);credits.debit(inviter,10,key());}));
            assertTrue(inviterLocked.await(5,TimeUnit.SECONDS));
            var welcome=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(t->grant.grant(invitee)));
            debit.get(5,TimeUnit.SECONDS);welcome.get(5,TimeUnit.SECONDS);
            assertEquals(110,credits.balance(inviter));assertEquals(50,credits.balance(invitee));
        }finally{welcomeCredited.countDown();pool.shutdownNow();}
    }
    private static void await(CountDownLatch latch){try{if(!latch.await(5,TimeUnit.SECONDS))throw new IllegalStateException("test coordination timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
}
