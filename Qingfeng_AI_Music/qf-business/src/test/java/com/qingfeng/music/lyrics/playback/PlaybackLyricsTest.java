package com.qingfeng.music.lyrics.playback;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlaybackLyricsTest {
    @Test void keepsRealTimestampsAndRemovesSectionMarkers(){
        var lines=PlaybackLyrics.lines(List.of(new PlaybackLyrics.Word("[Verse 1]\n第一句歌词\n",16.4,23.3),new PlaybackLyrics.Word("第二句歌词\n",24,30)));
        assertEquals(2,lines.size());assertEquals("第一句歌词",lines.get(0).text());assertEquals(16.4,lines.get(0).start());assertEquals(23.3,lines.get(0).end());assertEquals(24,lines.get(1).start());
    }
    @Test void groupsWordTimestampsIntoLinesWithoutInventingTiming(){
        var lines=PlaybackLyrics.lines(List.of(new PlaybackLyrics.Word("[Chorus]\nHello",2,2.4),new PlaybackLyrics.Word("world\n",2.5,3),new PlaybackLyrics.Word("Stay with me\n",8,10)));
        assertEquals("Hello world",lines.get(0).text());assertEquals(2,lines.get(0).start());assertEquals(3,lines.get(0).end());assertEquals(8,lines.get(1).start());
    }
    @Test void ignoresInvalidTimingAndNeverMakesUniformTimestamps(){
        var lines=PlaybackLyrics.lines(List.of(new PlaybackLyrics.Word("invalid",Double.NaN,3),new PlaybackLyrics.Word("negative",-1,3),new PlaybackLyrics.Word("backwards",4,2),new PlaybackLyrics.Word("valid\n",100,103)));
        assertEquals(1,lines.size());assertEquals(100,lines.get(0).start());assertEquals(103,lines.get(0).end());
    }
    @Test void overlappingWordIntervalsKeepTheWholeLineEnd(){var lines=PlaybackLyrics.lines(List.of(new PlaybackLyrics.Word("Hello",1,4),new PlaybackLyrics.Word("world\n",2,3)));assertEquals(4,lines.get(0).end());}
}
