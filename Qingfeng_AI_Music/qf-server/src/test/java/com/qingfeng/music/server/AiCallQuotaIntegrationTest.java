package com.qingfeng.music.server;

import com.qingfeng.music.agent.api.ReferenceAnalyst;
import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.credits.DailyQuota;
import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.lyrics.application.InspirationRewriter;
import com.qingfeng.music.settings.MusicBriefService;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 不直接计费的 AI 调用（音乐要求精简、参考音乐语义化）必须有每日次数上限，
 * 否则成本敞口没有上界。配额落在数据库上，因此跨实例有效。
 */
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:ai_call_quota;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "qingfeng.music-settings.simplify.daily-per-creator=2",
    "qingfeng.reference.analysis.daily-per-creator=2"})
@ActiveProfiles("test")
class AiCallQuotaIntegrationTest {
    @Autowired IdentityApi identity; @Autowired JdbcTemplate jdbc; @Autowired DailyQuota quota;

    @BeforeEach void reset(){ jdbc.update("DELETE FROM daily_quota"); }

    @Test void musicBriefIsCappedByTheDailyQuota(){
        var calls=new AtomicInteger();
        InspirationRewriter writer=new InspirationRewriter(){
            public boolean isConfigured(){return true;}
            public String rewrite(String t,String l){calls.incrementAndGet();return "90 BPM，不要吉他";}
        };
        var service=new MusicBriefService(writer,true,2,quota,2);
        String owner=identity.create().creatorId();
        assertEquals("90 BPM，不要吉他",service.suggest(owner,"温暖，90 BPM，不要吉他"));
        assertEquals("90 BPM，不要吉他",service.suggest(owner,"温暖，90 BPM，不要吉他"));
        var error=assertThrows(DomainException.class,()->service.suggest(owner,"温暖，90 BPM，不要吉他"));
        assertEquals("MUSIC_BRIEF_QUOTA_EXCEEDED",error.code());
        assertEquals(2,calls.get());
        assertEquals(0,quota.remaining(owner,DailyQuota.MUSIC_BRIEF,2));
    }

    @Test void referenceAnalysisIsCappedByTheDailyQuota(){
        var calls=new AtomicInteger();
        ReferenceAnalyst analyst=new ReferenceAnalyst(){
            public boolean isConfigured(){return true;}
            public Suggestion analyse(Request request){calls.incrementAndGet();return new Suggestion("pop","明亮",0,List.of(),List.of(),"轻快明亮");}
        };
        var service=new com.qingfeng.music.agent.application.ReferenceAnalysisService(analyst,true,2,quota,2);
        String owner=identity.create().creatorId();
        var request=new ReferenceAnalyst.Request("温暖", "ref.mp3", 30, "zh-CN", features());
        assertNotNull(service.analyse(owner,request));
        assertNotNull(service.analyse(owner,request));
        var error=assertThrows(DomainException.class,()->service.analyse(owner,request));
        assertEquals("REFERENCE_ANALYSIS_QUOTA_EXCEEDED",error.code());
        assertEquals(2,calls.get());
    }

    @Test void quotasAreScopedPerUserAndPerBucket(){
        String owner=identity.create().creatorId(), other=identity.create().creatorId();
        assertTrue(quota.consume(owner,DailyQuota.MUSIC_BRIEF,1));
        assertFalse(quota.consume(owner,DailyQuota.MUSIC_BRIEF,1));
        assertTrue(quota.consume(other,DailyQuota.MUSIC_BRIEF,1));
        // 不同桶互不影响。
        assertTrue(quota.consume(owner,DailyQuota.REFERENCE_ANALYSIS,1));
    }

    @Test void unlimitedAndZeroLimitsFollowTheProjectConvention(){
        String owner=identity.create().creatorId();
        assertTrue(quota.consume(owner,DailyQuota.MUSIC_BRIEF,-1));
        assertTrue(quota.consume(owner,DailyQuota.MUSIC_BRIEF,-1));
        // -1 表示不限（与 rewriteFreeRemaining 的约定一致，界面据此不显示剩余次数）。
        assertEquals(-1,quota.remaining(owner,DailyQuota.MUSIC_BRIEF,-1));
        assertFalse(quota.consume(owner,DailyQuota.REFERENCE_ANALYSIS,0));
        assertEquals(0,quota.remaining(owner,DailyQuota.REFERENCE_ANALYSIS,0));
    }

    static ReferenceAnalyst.Features features(){
        return new ReferenceAnalyst.Features(120,0.9,0.5,0.9,0.5,0.2,0.2,0.1,0.6,0.4,30,30);
    }
}
