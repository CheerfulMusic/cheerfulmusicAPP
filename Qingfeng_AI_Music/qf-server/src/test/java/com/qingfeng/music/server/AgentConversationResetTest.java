package com.qingfeng.music.server;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.infrastructure.persistence.agent.ConversationMapper;
import com.qingfeng.music.infrastructure.persistence.agent.JdbcConversationRepository;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentConversationResetTest {
    @Test void recentTurnPreventsResetAndDeletion(){
        var mapper=mock(ConversationMapper.class);var now=Instant.now();
        when(mapper.recentRunning("conversation",now.minusSeconds(600))).thenReturn(1);
        assertEquals("AGENT_TURN_IN_PROGRESS",assertThrows(DomainException.class,()->new JdbcConversationRepository(mapper).clear("conversation",now)).code());
        verify(mapper).lockForReset("conversation");verify(mapper,never()).archiveRequests(anyString());verify(mapper,never()).deleteMessages(anyString());
    }
    @Test void archiveIdempotencyBeforeClearingVisibleMessages(){
        var mapper=mock(ConversationMapper.class);var now=Instant.now();
        new JdbcConversationRepository(mapper).clear("conversation",now);
        var order=inOrder(mapper);order.verify(mapper).lockForReset("conversation");order.verify(mapper).recentRunning("conversation",now.minusSeconds(600));
        order.verify(mapper).archiveRequests("conversation");order.verify(mapper).deleteMessages("conversation");order.verify(mapper).clearWork("conversation",now);order.verify(mapper).clearState("conversation",now);
    }
}
