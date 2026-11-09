package com.qingfeng.music.settings;

import com.qingfeng.music.lyrics.application.InspirationRewriter;
import com.qingfeng.music.common.exception.DomainException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MusicBriefServiceTest {
    InspirationRewriter writer(String output){return new InspirationRewriter(){public boolean isConfigured(){return true;}public String rewrite(String input,String language){return output;}};}
    @Test void protectedRestrictionsAndTempoCannotDisappearFromAnAiSuggestion(){
        String original="温暖，90 BPM，不要吉他";
        assertThrows(DomainException.class,()->new MusicBriefService(writer("温暖，90 BPM，吉他"),true,1).suggest("owner",original));
        assertThrows(DomainException.class,()->new MusicBriefService(writer("温暖，不要吉他"),true,1).suggest("owner",original));
        assertEquals("90 BPM，不要吉他，温暖",new MusicBriefService(writer("90 BPM，不要吉他，温暖"),true,1).suggest("owner",original));
        assertEquals("温暖，90 BPM，不要吉他",original);
    }
    @Test void unavailableOrOversizedSuggestionsNeverBecomeUsableBriefs(){
        assertFalse(new MusicBriefService(writer("ok"),false,1).available());
        assertThrows(DomainException.class,()->new MusicBriefService(writer("x".repeat(301)),true,1).suggest("owner","温暖"));
    }
}
