package com.qingfeng.music.lyrics.application;
import com.qingfeng.music.common.exception.DomainException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;

class LyricsStructureTest {
    @Test void exactRequestedStructuresAndChorusMoveAreEnforced() {
        var sequences=Map.of(
            "AAB","Verse 1|Verse 2|Chorus|Interlude|Verse 2|Chorus|Chorus 2",
            "ABB","Verse|Chorus|Chorus 2|Interlude|Verse|Chorus|Chorus 2",
            "ABC","Verse|Pre-Chorus|Chorus|Interlude|Pre-Chorus|Chorus|Chorus 2",
            "ABCD","Verse|Pre-Chorus|Chorus|Interlude|Pre-Chorus|Chorus|Bridge|Chorus");
        sequences.forEach((id,sequence)->{
            var normal=new LyricsStructure(id,false); assertEquals(sequence,String.join("|",normal.sections()));
            var first=new LyricsStructure(id,true);
            var moved=new ArrayList<>(normal.sections()); moved.remove("Chorus"); moved.add(0,"Chorus");
            assertEquals(moved,first.sections()); assertEquals(normal.sections().size(),first.sections().size());
            for(var structure:List.of(normal,first)) {
                String lyrics=render(structure);
                assertDoesNotThrow(()->structure.validate(lyrics));
                assertThrows(DomainException.class,()->structure.validate(lyrics+"\n[Bridge]\nextra"));
                assertThrows(DomainException.class,()->structure.validate(lyrics.replace("[Interlude]","[Interlude]\n不应演唱")));
                assertThrows(DomainException.class,()->structure.validate(lyrics.replaceFirst("\\[Chorus]","[Bridge]")));
                assertTrue(structure.instruction().contains("[Chorus 2]")||id.equals("ABCD"));
            }
        });
    }
    static String render(LyricsStructure structure) {
        return String.join("\n",structure.sections().stream().map(s->"["+s+"]"+(s.equals("Interlude")?"":"\n唱出今天的故事")).toList());
    }
    @Test void invalidAndEmptySectionsCannotPassButLegacyAutoIsCompatible() {
        assertThrows(DomainException.class,()->new LyricsStructure("BAD",false));
        assertThrows(DomainException.class,()->new LyricsStructure("AUTO",true));
        assertEquals("",new LyricsStructure(null,false).instruction());
        assertDoesNotThrow(()->new LyricsStructure(null,false).validate("自由歌词"));
        var abc=new LyricsStructure("ABC",false);
        assertThrows(DomainException.class,()->abc.validate(render(abc).replaceFirst("唱出今天的故事", "")));
    }

    @Test void customStructureKeepsRepeatedSectionsAndAllowsAnInstrumentalEnding() {
        var selected=List.of("Chorus","Verse 2","Chorus","Interlude");
        var structure=new LyricsStructure("CUSTOM",false,selected);
        assertEquals(selected,structure.sections());
        assertTrue(structure.instruction().contains("[Chorus] → [Verse 2] → [Chorus] → [Interlude]"));
        var lyrics=render(structure);
        assertDoesNotThrow(()->structure.validate(lyrics));
        assertThrows(DomainException.class,()->structure.validate(lyrics+"\n这一段没有歌词"));
        assertThrows(DomainException.class,()->structure.validate(lyrics.replaceFirst("\\[Chorus]","[Verse 2]")));
        assertThrows(DomainException.class,()->structure.validate(lyrics.replaceFirst("\\[Chorus]\\n唱出今天的故事\\n","")));
    }

    @Test void customStructureRequiresSupportedSectionsAndAtLeastOneLyricSection() {
        assertThrows(DomainException.class,()->new LyricsStructure("CUSTOM",false));
        for(var invalid:List.of(List.<String>of(),List.of("Interlude","Interlude"),List.of("verse"),
            Arrays.asList("Verse",null),Collections.nCopies(LyricsStructure.MAX_CUSTOM_SECTIONS+1,"Verse"))) {
            assertEquals("INVALID_LYRICS_REQUEST",assertThrows(DomainException.class,
                ()->new LyricsStructure("CUSTOM",false,invalid)).code());
        }
        assertThrows(DomainException.class,()->new LyricsStructure("CUSTOM",true,List.of("Chorus")));
        assertThrows(DomainException.class,()->new LyricsStructure("AAB",false,List.of("Chorus")));
        assertDoesNotThrow(()->new LyricsStructure("CUSTOM",false,Collections.nCopies(LyricsStructure.MAX_CUSTOM_SECTIONS,"Verse")));
    }

    @Test void normalizesKnownStyleVariantsAndReinsertsAnInstrumentalSectionLocally() {
        var structure = new LyricsStructure("CUSTOM", false, List.of("Verse 1", "Interlude", "Chorus"));
        String normalized = LyricsStructure.normalizeHeaders(LyricsFormatting.unwrap("\uFEFF```lyrics\r\n## 【主歌１】\r\n雨停在窗边\r\n**［chorus］**\r\n风沿着原路回来\r\n```"));
        var prepared = structure.prepare(normalized);
        assertTrue(prepared.missing().isEmpty());
        assertEquals("[Verse 1]\n雨停在窗边\n[Interlude]\n[Chorus]\n风沿着原路回来", prepared.complete(Map.of()));
    }

    @Test void repairsExplicitEmptyRepeatedSlotAndRefusesAmbiguousOrMisplacedText() {
        var structure = new LyricsStructure("CUSTOM", false, List.of("Verse", "Chorus", "Chorus"));
        var prepared = structure.prepare("[Verse]\n天刚亮\n[Chorus]\n第一次副歌\n[Chorus]");
        assertEquals(Map.of(2, "Chorus"), prepared.missing());
        assertEquals("[Verse]\n天刚亮\n[Chorus]\n第一次副歌\n[Chorus]\n第二次副歌", prepared.complete(Map.of(2, "第二次副歌")));
        assertThrows(DomainException.class, () -> structure.prepare("[Verse]\n天刚亮\n[Chorus]\n哪一次副歌"));
        assertThrows(DomainException.class, () -> structure.prepare("[Chorus]\n副歌\n[Verse]\n主歌"));
        assertThrows(DomainException.class, () -> prepared.complete(Map.of(2, "## 【副歌】\n伪装的段落")));
        assertThrows(DomainException.class, () -> prepared.complete(Map.of(1, "试图覆盖已有歌词", 2, "第二次副歌")));
    }
}
