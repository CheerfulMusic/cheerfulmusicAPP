package com.qingfeng.music.server.agent;

import com.qingfeng.music.common.concurrent.AgentRequestScope;
import com.qingfeng.music.common.exception.DomainException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AgentChatExecutorTest {
    @Test void submitReturnsBeforeBlockingModelAndRejectsExcessWithoutAdmission()throws Exception{
        var admission=mock(AgentAdmission.class);var chat=mock(AgentChatService.class);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var ticket=new AgentAdmission.Ticket("id","owner",false);when(admission.acquire(anyString(),anyString(),anyString(),anyString(),any())).thenReturn(ticket);
        var snapshot=new AgentChatService.Snapshot(List.of(),null,null,null,null,false,null);
        when(chat.send(anyString(),anyString(),anyString(),anyString(),any())).thenAnswer(call->{entered.countDown();release.await(5,TimeUnit.SECONDS);return snapshot;});
        try(var executor=new AgentChatExecutor(admission,chat,1,Duration.ofSeconds(10),65536)){
            var result=executor.submit("owner","conversation","你好","key",null);assertTrue(entered.await(1,TimeUnit.SECONDS));assertFalse(result.hasResult());
            assertEquals("AGENT_BUSY",assertThrows(DomainException.class,()->executor.submit("other","other","你好","key2",null)).code());
            verify(admission,times(1)).acquire(anyString(),anyString(),anyString(),anyString(),any());release.countDown();
            for(int i=0;i<100&&!result.hasResult();i++)Thread.sleep(10);
            assertSame(snapshot,result.getResult());verify(admission).finish(ticket,true);
        }finally{release.countDown();}
    }
    @Test void cooperativeCancellationPreventsFurtherCallsButDoesNotInterruptCurrentSideEffect(){
        var scope=new AgentRequestScope(Duration.ofSeconds(10),2048);
        try(var ignored=scope.enter()){assertTrue(AgentRequestScope.timeout(Duration.ofSeconds(90)).compareTo(Duration.ofSeconds(10))<=0);scope.cancel();assertEquals("AGENT_DEADLINE_EXCEEDED",assertThrows(DomainException.class,AgentRequestScope::check).code());}
        assertDoesNotThrow(AgentRequestScope::check);
    }
}
