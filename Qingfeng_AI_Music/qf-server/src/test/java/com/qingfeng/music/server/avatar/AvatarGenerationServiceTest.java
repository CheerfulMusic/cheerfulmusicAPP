package com.qingfeng.music.server.avatar;

import com.qingfeng.music.common.exception.DomainException;
import java.io.ByteArrayInputStream;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AvatarGenerationServiceTest {
    JdbcTemplate db; AvatarService avatars; AvatarImageProvider provider; AvatarGenerationService service;
    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        db=new JdbcTemplate(ds); db.execute("CREATE TABLE creator (id VARCHAR(36) PRIMARY KEY)");
        db.execute("CREATE TABLE avatar_generation_task (id VARCHAR(36) PRIMARY KEY,creator_id VARCHAR(36) NOT NULL,request_key VARCHAR(36) NOT NULL,prompt VARCHAR(1000) NOT NULL,expected_version VARCHAR(36),status VARCHAR(24) NOT NULL,provider_task_id VARCHAR(255),lease_token VARCHAR(36),lease_until TIMESTAMP,next_attempt_at TIMESTAMP,failures INT NOT NULL DEFAULT 0,created_at TIMESTAMP NOT NULL,updated_at TIMESTAMP NOT NULL,UNIQUE(creator_id,request_key))");
        db.update("INSERT INTO creator(id) VALUES ('alice'),('bob')");
        avatars=mock(AvatarService.class); provider=mock(AvatarImageProvider.class);
        when(avatars.current(anyString())).thenReturn(new AvatarService.View(null,null)); when(provider.configured()).thenReturn(true);
        service=new AvatarGenerationService(db,new TransactionTemplate(new DataSourceTransactionManager(ds)),avatars,provider,true,3);
    }
    @Test void oneSubmissionPerRequestAndAccountIsolation() {
        String key=UUID.randomUUID().toString(); when(provider.submit(anyString())).thenReturn("provider-task");
        var first=service.start("alice",key,"一只猫"); var again=service.start("alice",key,"一只猫");
        assertEquals(first,again); verify(provider,times(1)).submit("一只猫");
        assertEquals("RUNNING",first.status()); assertEquals(new AvatarGenerationService.View(null,"NONE",true),service.latest("bob"));
        assertThrows(DomainException.class,()->service.start("alice",key,"不同的猫"));
        assertThrows(DomainException.class,()->service.start("alice",UUID.randomUUID().toString(),"新头像"));
    }
    @Test void ambiguousSubmitIsNeverRetried() {
        String key=UUID.randomUUID().toString(); when(provider.submit(anyString())).thenThrow(new IllegalStateException());
        assertEquals("UNKNOWN",service.start("alice",key,"猫").status());
        assertEquals("UNKNOWN",service.start("alice",key,"猫").status());
        assertThrows(DomainException.class,()->service.start("alice",UUID.randomUUID().toString(),"猫"));
        service.runOnce(); verify(provider,times(1)).submit(anyString()); verify(provider,never()).query(anyString());
    }
    @Test void definitivePreDispatchRejectionIsFailedWithoutAutomaticRetry() {
        String key=UUID.randomUUID().toString(); when(provider.submit(anyString())).thenThrow(new AvatarImageProvider.Rejected());
        assertEquals("FAILED",service.start("alice",key,"猫").status());
        assertEquals("FAILED",service.start("alice",key,"猫").status());
        service.runOnce(); verify(provider,times(1)).submit(anyString()); verify(provider,never()).query(anyString());
    }
    @Test void uploadDuringGenerationIsNotOverwritten() throws Exception {
        when(avatars.current("alice")).thenReturn(new AvatarService.View("old","old-version"));
        when(provider.submit(anyString())).thenReturn("remote"); var task=service.start("alice",UUID.randomUUID().toString(),"猫");
        when(provider.query("remote")).thenReturn(new AvatarImageProvider.Result("SUCCEEDED","https://provider.example/image"));
        when(provider.download(anyString())).thenReturn(new ByteArrayInputStream(new byte[]{1}));
        when(avatars.saveIfVersion(eq("alice"),eq("old-version"),any(),eq(task.id()))).thenReturn(Optional.empty());
        when(avatars.current("alice")).thenReturn(new AvatarService.View("new","new-version"));
        service.runOnce(); assertEquals("SUPERSEDED",service.latest("alice").status());
    }
    @Test void crashAfterAvatarCommitRecoversWithoutGeneratingOrSavingAgain() {
        when(provider.submit(anyString())).thenReturn("remote"); var task=service.start("alice",UUID.randomUUID().toString(),"猫");
        when(avatars.current("alice")).thenReturn(new AvatarService.View("image",task.id()));
        service.runOnce(); assertEquals("SUCCEEDED",service.latest("alice").status());
        verify(provider,never()).query(anyString());
    }
    @Test void repeatedProviderReadFailuresAreBoundedAndNeverResubmit() {
        when(provider.submit(anyString())).thenReturn("remote"); var task=service.start("alice",UUID.randomUUID().toString(),"猫");
        when(provider.query("remote")).thenThrow(new IllegalStateException());
        for(int i=0;i<5;i++) { service.runOnce(); db.update("UPDATE avatar_generation_task SET next_attempt_at=NULL WHERE id=?",task.id()); }
        assertEquals("UNKNOWN",service.latest("alice").status());
        service.runOnce(); verify(provider,times(5)).query("remote"); verify(provider,times(1)).submit(anyString());
    }
    @Test void runningTasksPollSlowlyAndBecomeUnknownAfterDeadline() {
        when(provider.submit(anyString())).thenReturn("remote"); var task=service.start("alice",UUID.randomUUID().toString(),"猫");
        when(provider.query("remote")).thenReturn(new AvatarImageProvider.Result("RUNNING",null));
        service.runOnce(); service.runOnce(); verify(provider,times(1)).query("remote");
        db.update("UPDATE avatar_generation_task SET created_at=? WHERE id=?",java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(3700)),task.id());
        service.runOnce(); assertEquals("UNKNOWN",service.latest("alice").status());
        verify(provider,times(1)).submit(anyString()); verify(provider,times(1)).query(anyString());
    }
    @Test void invalidGeneratedImageTerminatesAsFailed() throws Exception {
        when(provider.submit(anyString())).thenReturn("remote"); var task=service.start("alice",UUID.randomUUID().toString(),"猫");
        when(provider.query("remote")).thenReturn(new AvatarImageProvider.Result("SUCCEEDED","https://provider.example/image"));
        when(provider.download(anyString())).thenReturn(new ByteArrayInputStream(new byte[]{1}));
        when(avatars.saveIfVersion(eq("alice"),isNull(),any(),eq(task.id()))).thenThrow(new DomainException("INVALID_COVER_IMAGE","invalid"));
        service.runOnce(); assertEquals("FAILED",service.latest("alice").status());
    }
    @Test void disabledProviderFailsBeforeCreatingAnyTask() {
        when(provider.configured()).thenReturn(false);
        assertThrows(DomainException.class,()->service.start("alice",UUID.randomUUID().toString(),"猫"));
        assertEquals("NONE",service.latest("alice").status()); verify(provider,never()).submit(anyString());
    }
    @Test void accountsHaveBoundedPlatformFundedAttempts() {
        when(provider.submit(anyString())).thenReturn("remote");
        when(provider.query(anyString())).thenReturn(new AvatarImageProvider.Result("FAILED",null));
        for(int i=0;i<3;i++){service.start("alice",UUID.randomUUID().toString(),"猫");service.runOnce();}
        assertThrows(DomainException.class,()->service.start("alice",UUID.randomUUID().toString(),"猫"));
        assertEquals("RUNNING",service.start("bob",UUID.randomUUID().toString(),"猫").status());
        verify(provider,times(4)).submit("猫");
    }
    @Test void avatarIntentRequiresExplicitRequestAndConfirmation() {
        assertTrue(com.qingfeng.music.agent.application.AgentChatPolicy.actionRequested("generate_avatar","帮我生成一个可爱的猫咪头像"));
        assertFalse(com.qingfeng.music.agent.application.AgentChatPolicy.actionRequested("generate_avatar","不要生成头像"));
        assertFalse(com.qingfeng.music.agent.application.AgentChatPolicy.actionRequested("generate_avatar","生成头像怎么操作？"));
        assertTrue(com.qingfeng.music.agent.application.AgentChatPolicy.requiresConfirmation("generate_avatar"));
    }
    @Test void actionReportsRealTaskOutcome() {
        var generator=mock(AvatarGenerationService.class);
        var action=new AgentAvatarActions(generator);
        when(generator.start(anyString(),anyString(),anyString())).thenReturn(new AvatarGenerationService.View("t","FAILED",true),new AvatarGenerationService.View("t","SUPERSEDED",true),new AvatarGenerationService.View("t","SUCCEEDED",true));
        assertTrue(action.execute("alice","c","generate_avatar",java.util.Map.of("prompt","猫"),"",UUID.randomUUID().toString(),null).reply().contains("没有生成"));
        assertTrue(action.execute("alice","c","generate_avatar",java.util.Map.of("prompt","猫"),"",UUID.randomUUID().toString(),null).reply().contains("保留"));
        assertTrue(action.execute("alice","c","generate_avatar",java.util.Map.of("prompt","猫"),"",UUID.randomUUID().toString(),null).reply().contains("已生成"));
    }
}
