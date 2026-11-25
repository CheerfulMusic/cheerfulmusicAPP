package com.qingfeng.music.server;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.lyrics.application.InspirationRewriteService;
import com.qingfeng.music.lyrics.application.InspirationRewriter;
import com.qingfeng.music.lyrics.application.LyricLineRewriteService;
import com.qingfeng.music.lyrics.application.LyricRhymePreview;
import com.qingfeng.music.work.api.WorkApi;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static com.qingfeng.music.lyrics.application.LyricRhymePreview.Status.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LyricRhymePreviewTest {
    private final WorkApi works = mock(WorkApi.class);
    private final AtomicInteger modelCalls = new AtomicInteger();

    @Test void findsMandarinReferenceAndOnlyReturnsVerifiedImprovement() {
        var service = service("我多想再拥抱着你\n我想再走那条街道\n我多想还能见到你\n多想再给你个拥抱");
        var input = new LyricRhymePreview.Request("我多想再拥抱着你",
            List.of("你说春天总会再来", "我还记得那片花海", "晚风吹过空荡街道", "我多想再拥抱着你"),
            3, null, "zh-CN", false, false, false);
        UUID requestId = UUID.randomUUID();
        var result = service.previewRhyme("owner", "work", requestId, input);

        assertEquals(SUGGESTIONS, result.status());
        assertEquals(2, result.referenceIndex());
        assertEquals("道", result.reference().word());
        assertEquals("你", result.original().word());
        assertEquals(List.of("多想再给你个拥抱"), result.candidates().stream().map(LyricRhymePreview.Candidate::text).toList());
        assertEquals(8, result.candidates().get(0).syllables());
        assertEquals(result, service.previewRhyme("owner", "work", requestId, input));
        assertEquals(1, modelCalls.get());
        verify(works, never()).update(any(), any(), anyLong(), any());
    }

    @Test void allowsEnglishOverrideWhenAutomaticReferenceIsUnclear() {
        var service = service("I stayed awake till noon\nI stayed awake all night\nI lay awake through the long and bitter night");
        var lines = List.of("I watched the fading light", "The city falls asleep", "I stayed awake till dawn");
        var auto = new LyricRhymePreview.Request(lines.get(2), lines, 2, null, "en", false, false, false);
        assertEquals(CHOOSE_REFERENCE, preview(service, auto).status());
        assertEquals(0, modelCalls.get());

        var explicit = new LyricRhymePreview.Request(lines.get(2), lines, 2, 0, "en", false, false, false);
        var result = preview(service, explicit);
        assertEquals(SUGGESTIONS, result.status());
        assertEquals("night", result.candidates().get(0).ending().word());
        assertEquals(6, result.candidates().get(0).syllables());
        assertEquals(1, result.candidates().size());
    }

    @Test void crossLanguageNearRhymeIsOptInAndKeepsTheLineLanguage() {
        var service = service("我还在等你回来\nI never said goodbye\n我还是没说 goodbye");
        var lines = List.of("我还在等你回来", "我还是没说 farewell");
        var strict = new LyricRhymePreview.Request(lines.get(1), lines, 1, 0, "mixed", false, false, false);
        assertEquals(NO_MATCH, preview(service, strict).status());
        assertEquals(0, modelCalls.get());

        var permissive = new LyricRhymePreview.Request(lines.get(1), lines, 1, 0, "mixed", false, true, false);
        var result = preview(service, permissive);
        assertEquals(SUGGESTIONS, result.status());
        assertEquals(1, result.candidates().size());
        assertEquals("CROSS_LANGUAGE", result.candidates().get(0).match().name());
        assertEquals("goodbye", result.candidates().get(0).ending().word());
    }

    @Test void offersModelSuggestionsForSpanishWithoutClaimingDictionaryVerification() {
        var lines = List.of("La luna canta sobre el mar", "Espero que regreses pronto");
        var service = service("Espero volverte a encontrar\nLa luna canta sobre el mar");
        var request = new LyricRhymePreview.Request(lines.get(1), lines, 1, null, "es", false, false, false);

        var result = preview(service, request);

        assertEquals(SUGGESTIONS, result.status());
        assertEquals(0, result.referenceIndex());
        assertNull(result.reference());
        assertEquals(1, result.candidates().size());
        assertEquals("Espero volverte a encontrar", result.candidates().get(0).text());
        assertEquals("SUGGESTED", result.candidates().get(0).match().name());
        assertEquals(1, modelCalls.get());
    }

    @Test void alreadyRhymingAndInvalidReferenceDoNotCallModel() {
        var service = service("unneeded");
        var lines = List.of("I watched the fading light", "I stayed awake all night");
        var input = new LyricRhymePreview.Request(lines.get(1), lines, 1, null, "en", false, false, false);
        assertEquals(ALREADY_RHYMES, preview(service, input).status());
        var invalid = new LyricRhymePreview.Request(lines.get(1), lines, 1, 1, "en", false, false, false);
        assertThrows(DomainException.class, () -> preview(service, invalid));
        assertEquals(0, modelCalls.get());
    }

    @Test void explicitlyRewritesAnAlreadyRhymingLineWithoutWeakeningTheRhyme() {
        var service = service("让我再看一会儿海。\n我还想望着潮汐\n我想再看一会儿海");
        var lines = List.of("让我再看一会儿海", "看那浪涌上来又退开");
        var input = new LyricRhymePreview.Request(lines.get(0), lines, 0, null, "zh-CN", true, false, true);
        var result = preview(service, input);

        assertEquals(SUGGESTIONS, result.status());
        assertEquals("开", result.reference().word());
        assertEquals("海", result.original().word());
        assertEquals(List.of("我想再看一会儿海"), result.candidates().stream().map(LyricRhymePreview.Candidate::text).toList());
        assertEquals("EXACT", result.candidates().get(0).match().name());
        assertEquals(1, modelCalls.get());
    }

    private LyricLineRewriteService service(String output) {
        var writer = new InspirationRewriter() {
            public boolean isConfigured() { return true; }
            public String rewrite(String text, String language) {
                assertTrue(text.startsWith("操作：RHYME_CANDIDATES\n"));
                modelCalls.incrementAndGet();
                return output;
            }
        };
        return new LyricLineRewriteService(works, new InspirationRewriteService(writer, true, 1), 12);
    }

    private LyricRhymePreview.Result preview(LyricLineRewriteService service, LyricRhymePreview.Request input) {
        return service.previewRhyme("owner", "work", UUID.randomUUID(), input);
    }
}
