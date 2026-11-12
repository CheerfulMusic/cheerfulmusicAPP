package com.qingfeng.music.server.payment;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Same account/replay/lock-order suite against the explicitly isolated local MySQL test port only. */
@EnabledIfEnvironmentVariable(named="QF_EXECUTION_MYSQL_TEST_PORT",matches="57[0-9]{3}")
@SpringBootTest(properties={
    "spring.datasource.url=jdbc:mysql://127.0.0.1:${QF_EXECUTION_MYSQL_TEST_PORT}/qf_execution_concurrency_test?serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false",
    "spring.datasource.username=root","spring.datasource.password=","spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
    "qingfeng.payment.inbox.scheduler-enabled=false"})
class CreditAccountMySqlTest extends CreditAccountConcurrencyTest {
    /** InnoDB current reads deliberately differ from H2 repeatable-read snapshot semantics. */
    @Test void anOuterRepeatableReadSnapshotCannotReplaceAnAlreadyInitializedBalance() throws Exception {
        String owner=owner();var pool=Executors.newSingleThreadExecutor();
        var transaction=new TransactionTemplate(manager);transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try{
            transaction.executeWithoutResult(t->{
                assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM credit_account WHERE owner_id=?",Integer.class,owner));
                try{pool.submit(()->credits.credit(owner,70,key())).get(5,TimeUnit.SECONDS);}catch(Exception e){throw new IllegalStateException(e);}
                credits.debit(owner,20,key());
            });
            assertEquals(50,credits.balance(owner));
            assertEquals(50,db.queryForObject("SELECT SUM(amount) FROM credit_ledger WHERE owner_id=?",Long.class,owner));
        }finally{pool.shutdownNow();}
    }
}
