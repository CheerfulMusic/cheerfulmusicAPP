package com.qingfeng.music.server.artist;

import com.qingfeng.music.generation.api.GenerationApi;
import com.qingfeng.music.generation.application.ExecutionRepository;
import com.qingfeng.music.generation.application.GenerationService;
import com.qingfeng.music.generation.application.port.MusicProvider;
import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.infrastructure.persistence.generation.MyBatisTaskRepository;
import com.qingfeng.music.infrastructure.persistence.generation.TaskMapper;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.media.application.MediaService;
import com.qingfeng.music.work.api.WorkApi;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
class SongPublicationIntegrationTest {
    @Autowired ArtistProfileService profiles;
    @Autowired IdentityApi identity;
    @Autowired WorkApi works;
    @Autowired LyricsApi lyrics;
    @Autowired TaskMapper tasks;
    @Autowired ExecutionRepository execution;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired MediaService media;

    @Test void publicGenerationPublishesOneArchivedVersionAndCanBeMadePrivate() throws Exception {
        var song = generate(true);
        assertTrue(profiles.selection(song.owner(), song.work()).publicationPending());
        assertTrue(profiles.readyPublications().isEmpty());
        assertFalse(profiles.mine(song.owner()).published());

        String asset = archive(song);
        var job = profiles.readyPublications().get(0);
        profiles.finishPublication(job);
        profiles.finishPublication(job);
        var published = profiles.selection(song.owner(), song.work());
        assertFalse(published.publicationPending());
        assertTrue(published.published());
        assertTrue(published.song().onDiscover());
        assertEquals(asset, published.song().assetId());
        assertEquals(1, published.song().version());
        assertEquals("Publication Artist", profiles.publishedProfile(published.publicId()).stageName());

        assertNull(profiles.makePrivate(song.owner(), song.work()).song());
        assertFalse(profiles.makePrivate(song.owner(), song.work()).publicationPending());
        assertTrue(profiles.publishedProfile(published.publicId()).songs().isEmpty());
        assertEquals(asset, media.list(song.owner(), song.work()).get(0).id());

        assertTrue(profiles.publishEverywhere(song.owner(), song.work(), asset, null, song.task().title(), null)
                .song().onDiscover());
    }

    @Test void privateGenerationDoesNotQueuePublicationAndCanLaterPublishWithAnArtistName() throws Exception {
        var song = generate(false);
        String asset = archive(song);
        assertTrue(profiles.readyPublications().isEmpty());
        assertFalse(profiles.selection(song.owner(), song.work()).published());
        assertNull(profiles.selection(song.owner(), song.work()).song());

        var published = profiles.publishEverywhere(song.owner(), song.work(), asset, null, song.task().title(), "Later Artist");
        assertTrue(published.published());
        assertTrue(published.song().onDiscover());
    }

    @Test void changingToPrivateCancelsEvenAPublicationAlreadyReadByTheWorker() throws Exception {
        var song = generate(true);
        archive(song);
        var job = profiles.readyPublications().get(0);
        profiles.makePrivate(song.owner(), song.work());
        profiles.finishPublication(job);
        var current = profiles.selection(song.owner(), song.work());
        assertFalse(current.publicationPending());
        assertFalse(current.published());
        assertNull(current.song());
    }

    private record Song(String owner, String work, GenerationApi.Task task) {}

    private Song generate(boolean publicly) {
        String owner = identity.create().creatorId();
        jdbc.update("""
                INSERT INTO creator_oidc_identity(subject_hash,creator_id,display_name,created_at,updated_at)
                VALUES(?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, UUID.randomUUID().toString(), owner, "Fixture account");
        var work = works.create(owner, new WorkApi.Draft("Friend", "生日", "Story", "Message", "folk", "Fixture song"));
        var revision = lyrics.save(owner, work.id(), work.version(), "Fixture song", "[Verse] Summer night");
        lyrics.confirm(owner, work.id(), revision.id());
        MusicProvider provider = mock(MusicProvider.class);
        when(provider.isConfigured()).thenReturn(true);
        when(provider.name()).thenReturn("publication-test");
        var generation = new GenerationService(works, lyrics, provider, new MyBatisTaskRepository(tasks), execution);
        String key = UUID.randomUUID().toString();
        var transaction = new TransactionTemplate(transactionManager);
        GenerationApi.Task task = transaction.execute(status -> publicly
                ? generation.submit(owner, work.id(), revision.id(), key,
                    created -> profiles.queuePublication(owner, created, "Publication Artist"))
                : generation.submit(owner, work.id(), revision.id(), key));
        assertNotNull(task);
        var replay = transaction.execute(status -> generation.submit(owner, work.id(), revision.id(), key,
                created -> fail("A replay must not change publication intent")));
        assertEquals(task.id(), replay.id());
        return new Song(owner, work.id(), task);
    }

    private String archive(Song song) throws Exception {
        var wav = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(40)
                .put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                .putInt(16).putShort((short)1).putShort((short)1).putInt(8000).putInt(16000)
                .putShort((short)2).putShort((short)16)
                .put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(4).putInt(0);
        String asset = media.store(song.task().id(), song.work(), new ByteArrayInputStream(wav.array()));
        jdbc.update("UPDATE generation_task SET status='SUCCEEDED',asset_id=? WHERE id=?", asset, song.task().id());
        return asset;
    }
}
