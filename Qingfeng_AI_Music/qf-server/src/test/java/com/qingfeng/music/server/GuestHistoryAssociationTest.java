package com.qingfeng.music.server;

import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.server.security.LogtoIdentityService;
import com.qingfeng.music.work.api.WorkApi;
import com.qingfeng.music.credits.CreditsLedger;
import java.sql.Timestamp;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test")
class GuestHistoryAssociationTest {
    @Autowired IdentityApi identity;
    @Autowired LogtoIdentityService logto;
    @Autowired WorkApi works;
    @Autowired JdbcTemplate db;
    @Autowired CreditsLedger credits;
    @Autowired com.qingfeng.music.usage.UsageBudget budget;
    @Autowired com.qingfeng.music.infrastructure.persistence.media.MediaMapper media;
    private final WorkApi.Draft draft=new WorkApi.Draft("家人","生日","一起旅行","谢谢","流行","旧歌");
    private IdentityApi.Session login(IdentityApi.Session browser,String subject,boolean google) {
        return google?identity.signInWithGoogle(browser.token(),browser.creatorId(),subject,null,null)
            :logto.signIn(browser.token(),browser.creatorId(),"https://provider.test/oidc",subject,null,null,null);
    }
    @Test void retiredGuestCannotReserveQuotaOrIssueAnotherRecoveryCode() {
        var first=identity.create();String subject=UUID.randomUUID().toString();
        var account=login(first,subject,true);var guest=identity.create();var song=works.create(guest.creatorId(),draft);
        login(guest,subject,true);
        var failure=assertThrows(com.qingfeng.music.common.exception.DomainException.class,
            ()->budget.reserve(guest.creatorId(),song.id(),UUID.randomUUID().toString(),com.qingfeng.music.usage.UsageBudget.Kind.MUSIC));
        assertEquals("CREATOR_SESSION_CHANGED",failure.code());
        assertThrows(com.qingfeng.music.common.exception.DomainException.class,()->identity.issueRecoveryCode(guest.creatorId()));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM model_usage_reservation WHERE work_id=?",Integer.class,song.id()));
        assertEquals(song.id(),works.get(account.creatorId(),song.id()).id());
    }
    @Test void bothLoginPathsKeepAccountAndGuestSongsAudioLinksAndUsageWithoutAnotherBonus() {
        for(boolean google:new boolean[]{true,false}) {
            String subject=UUID.randomUUID().toString();var account=login(identity.create(),subject,google);
            var old=works.create(account.creatorId(),draft);
            var guest=identity.create();var song=works.create(guest.creatorId(),draft);
            var unrelated=identity.create();var unrelatedSong=works.create(unrelated.creatorId(),draft);
            String recovery=identity.issueRecoveryCode(guest.creatorId());var guestDevice=identity.recover(recovery);
            String outstandingRecovery=identity.issueRecoveryCode(guest.creatorId());
            String asset=UUID.randomUUID().toString(),share=UUID.randomUUID().toString(),request=UUID.randomUUID().toString();
            var now=Timestamp.from(Instant.now());
            db.update("INSERT INTO media_asset(id,work_id,storage_key,origin,content_type,byte_size,sha256,created_at) VALUES(?,?,?,?,?,?,?,?)",
                asset,song.id(),"audio/"+asset,"GENERATED","audio/mpeg",123L,"a".repeat(64),now);
            db.update("UPDATE creator SET media_bytes=456 WHERE id=?",guest.creatorId());
            db.update("INSERT INTO share_link(id,work_id,token_hash,asset_id,title,recipient,message,lyrics,audio_origin,active,created_at,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                share,song.id(),UUID.randomUUID().toString(),asset,"旧歌","家人","谢谢","歌词","GENERATED",true,now,Timestamp.from(Instant.now().plusSeconds(3600)));
            db.update("INSERT INTO model_usage_reservation(kind,work_id,request_key,owner_id,usage_day,created_at) VALUES(?,?,?,?,?,?)",
                "MUSIC",song.id(),request,guest.creatorId(),java.sql.Date.valueOf(LocalDate.now()),now);
            long balance=credits.balance(account.creatorId());
            var signed=login(guest,subject,google);
            assertEquals(account.creatorId(),signed.creatorId());
            assertEquals(song.id(),works.get(signed.creatorId(),song.id()).id());
            assertEquals(old.id(),works.get(signed.creatorId(),old.id()).id());
            assertEquals(unrelatedSong.id(),works.get(unrelated.creatorId(),unrelatedSong.id()).id());
            assertTrue(works.list(guest.creatorId(),0).isEmpty());
            assertTrue(identity.resolve(guest.token()).isEmpty());assertTrue(identity.resolve(guestDevice.token()).isEmpty());
            assertThrows(RuntimeException.class,()->identity.recover(outstandingRecovery));
            assertEquals(asset,db.queryForObject("SELECT asset_id FROM share_link WHERE id=?",String.class,share));
            assertEquals(song.id(),db.queryForObject("SELECT work_id FROM media_asset WHERE id=?",String.class,asset));
            assertEquals(signed.creatorId(),db.queryForObject("SELECT owner_id FROM model_usage_reservation WHERE work_id=?",String.class,song.id()));
            assertEquals(456L,db.queryForObject("SELECT media_bytes FROM creator WHERE id=?",Long.class,signed.creatorId()));
            assertEquals(0L,db.queryForObject("SELECT media_bytes FROM creator WHERE id=?",Long.class,guest.creatorId()));
            assertEquals(balance,credits.balance(signed.creatorId()));
            assertThrows(RuntimeException.class,()->login(guest,subject,google));
            media.release(song.id(),333); // In-flight archive compensation follows the new work owner.
            assertEquals(123L,db.queryForObject("SELECT media_bytes FROM creator WHERE id=?",Long.class,signed.creatorId()));
            var repeated=login(identity.create(),subject,google);
            assertEquals(signed.creatorId(),repeated.creatorId());
            assertEquals(123L,db.queryForObject("SELECT media_bytes FROM creator WHERE id=?",Long.class,repeated.creatorId()));
        }
    }
}
