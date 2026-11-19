package com.qingfeng.music.server;

import com.qingfeng.music.common.concurrent.AgentRequestScope;
import com.qingfeng.music.common.concurrent.LyricsRequestBudget;
import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.lyrics.application.FallbackLyricsWriter;
import com.qingfeng.music.lyrics.application.LyricsWriter;
import com.qingfeng.music.work.api.WorkApi;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LyricsRequestBudgetTest {
    @Test void nestedBudgetsCannotExtendTheParentAndClosingRestoresIt() {
        try (var parent = LyricsRequestBudget.enter(Duration.ofSeconds(20))) {
            long original = LyricsRequestBudget.deadline(Duration.ofHours(1));
            try (var larger = LyricsRequestBudget.enter(Duration.ofMinutes(5))) {
                assertEquals(original, LyricsRequestBudget.deadline(Duration.ofHours(1)));
            }
            try (var shorter = LyricsRequestBudget.enter(Duration.ofSeconds(2))) {
                assertTrue(LyricsRequestBudget.deadline(Duration.ofHours(1)) < original);
            }
            assertEquals(original, LyricsRequestBudget.deadline(Duration.ofHours(1)));
        }
        long before = System.nanoTime();
        long reset = LyricsRequestBudget.deadline(Duration.ofMinutes(1));
        assertTrue(reset >= before + Duration.ofMinutes(1).toNanos(), "Closed request must not leak its old deadline");
    }

    @Test void providerFallbackKeepsTheOriginalDeadlineAndRestoresAnOuterBudget() {
        var primary = mock(LyricsWriter.class);
        var backup = mock(LyricsWriter.class);
        var work = mock(WorkApi.Work.class);
        var first = new AtomicLong();
        var second = new AtomicLong();
        when(backup.isConfigured()).thenReturn(true);
        when(primary.isConfigured()).thenReturn(true);
        when(primary.source()).thenReturn("OPENAI");
        when(backup.source()).thenReturn("QWEN");
        when(primary.write(work, "zh-CN")).thenAnswer(call -> {
            first.set(LyricsRequestBudget.deadline(Duration.ofHours(1)));
            throw new DomainException("LYRICS_PROVIDER_REJECTED", "Rejected before fallback");
        });
        when(backup.write(work, "zh-CN")).thenAnswer(call -> {
            second.set(LyricsRequestBudget.deadline(Duration.ofHours(1)));
            return new LyricsWriter.Draft("test", "words");
        });
        try (var parent = LyricsRequestBudget.enter(Duration.ofSeconds(45))) {
            long original = LyricsRequestBudget.deadline(Duration.ofHours(1));
            assertEquals("QWEN", new FallbackLyricsWriter(primary, backup).write(work, "zh-CN").source());
            assertEquals(original, first.get());
            assertEquals(first.get(), second.get(), "Fallback must not restart a fresh 110-second deadline");
            assertEquals(original, LyricsRequestBudget.deadline(Duration.ofHours(1)));
        }
        verify(primary, times(1)).write(work, "zh-CN");
        verify(backup, times(1)).write(work, "zh-CN");
    }

    @Test void confirmedInvalidResponseDoesNotStartBackupWhenTooLittleTimeRemains() {
        var primary = mock(LyricsWriter.class);
        var backup = mock(LyricsWriter.class);
        var work = mock(WorkApi.Work.class);
        when(primary.isConfigured()).thenReturn(true);
        when(backup.isConfigured()).thenReturn(true);
        when(primary.write(work, "zh-CN")).thenThrow(new DomainException("LYRICS_RESPONSE_INVALID", "invalid"));
        try (var parent = LyricsRequestBudget.enter(Duration.ofSeconds(10))) {
            assertEquals("LYRICS_RESPONSE_INVALID", assertThrows(DomainException.class,
                () -> new FallbackLyricsWriter(primary, backup).write(work, "zh-CN")).code());
        }
        verify(backup, never()).write(any(), anyString());
    }

    @Test void lyricBudgetDoesNotReplaceOrClearTheAgentCancellationScope() {
        try (var agent = new AgentRequestScope(Duration.ofSeconds(30), 5000).enter()) {
            try (var lyrics = LyricsRequestBudget.enter(Duration.ofSeconds(110))) {
                assertTrue(AgentRequestScope.timeout(Duration.ofMinutes(5)).compareTo(Duration.ofSeconds(30)) <= 0);
            }
            agent.cancel();
            assertEquals("AGENT_DEADLINE_EXCEEDED", assertThrows(DomainException.class, AgentRequestScope::check).code());
        }
    }

    @Test void budgetIsThreadLocalAndInvalidOrOutOfOrderUseCannotClearAnotherScope() {
        assertThrows(IllegalArgumentException.class, () -> LyricsRequestBudget.enter(Duration.ZERO));
        try (var outer = LyricsRequestBudget.enter(Duration.ofSeconds(5))) {
            long parent = LyricsRequestBudget.deadline(Duration.ofHours(1));
            long other = CompletableFuture.supplyAsync(() -> LyricsRequestBudget.deadline(Duration.ofHours(1))).join();
            assertTrue(other > parent, "Unrelated worker must not inherit a lyric request budget");
            try (var inner = LyricsRequestBudget.enter(Duration.ofSeconds(1))) {
                assertThrows(IllegalStateException.class, outer::close);
            }
            assertEquals(parent, LyricsRequestBudget.deadline(Duration.ofHours(1)));
        }
    }
}
