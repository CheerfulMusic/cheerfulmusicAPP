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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
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
    @Value("${local.server.port}") int port;

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
        assertNull(profiles.completePublication(song.owner(), song.work()).song());
        profiles.finishPublication(job);
        var current = profiles.selection(song.owner(), song.work());
        assertFalse(current.publicationPending());
        assertFalse(current.published());
        assertNull(current.song());
    }

    @Test void resultPageCompletesOnlyItsOwnersQueuedArchivedSong() throws Exception {
        var song = generate(true);
        var privateSong = generate(false);
        assertTrue(profiles.completePublication(song.owner(), song.work()).publicationPending());
        String asset = archive(song);
        archive(privateSong);
        var queued = profiles.readyPublications().get(0);

        var http = HttpClient.newHttpClient();
        assertEquals(403, complete(http, song, song, false).statusCode());
        assertEquals(200, complete(http, privateSong, song, true).statusCode());
        assertNull(profiles.completePublication(privateSong.owner(), privateSong.work()).song());
        assertNull(profiles.selection(song.owner(), song.work()).song());

        var response = complete(http, song, song, true);
        assertEquals(200, response.statusCode(), response.body());
        var published = profiles.selection(song.owner(), song.work());
        assertFalse(published.publicationPending());
        assertEquals(asset, published.song().assetId());
        assertTrue(profiles.discover(false, false, "", song.task().title(), 0).songs().stream()
                .anyMatch(entry -> entry.song().id().equals(published.song().id())));
        profiles.finishPublication(queued);
        assertEquals(published, profiles.completePublication(song.owner(), song.work()));

        profiles.makePrivate(song.owner(), song.work());
        assertNull(profiles.completePublication(song.owner(), song.work()).song());
        assertFalse(profiles.discover(false, false, "", song.task().title(), 0).songs().stream()
                .anyMatch(entry -> entry.song().id().equals(published.song().id())));
    }

    @Test
    void discoverDetailSupportsPublicReadsAndVersionScopedAccountInteractions() throws Exception {
        var owner = generate(false);
        var listener = generate(false);
        var guest = identity.create();
        String asset = archive(owner);
        var release = profiles.publishEverywhere(owner.owner(), owner.work(), asset, null, "Published song", "Publication Artist").song();
        String path = "/discover/songs/" + release.id() + "/" + release.version();
        String publicApi = "/api/public" + path;
        String accountApi = "/api/app" + path;
        var http = HttpClient.newHttpClient();

        var beforeMigration = interact(http, "GET", publicApi, null, true, null);
        assertEquals(200, beforeMigration.statusCode(), beforeMigration.body());
        assertTrue(beforeMigration.body().contains("Published song"));
        assertTrue(beforeMigration.body().contains("Summer night"));
        assertTrue(beforeMigration.body().contains("\"communityAvailable\":false"));
        assertEquals(503, interact(http, "GET", publicApi + "/comments", null, true, null).statusCode());
        assertEquals(503, interact(http, "PUT", accountApi + "/like", listener.session(), true, "{\"active\":true}").statusCode());

        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V56__discover_song_community.sql"))
                .execute(java.util.Objects.requireNonNull(jdbc.getDataSource()));
        var detail = interact(http, "GET", publicApi, null, true, null);
        assertEquals(200, detail.statusCode(), detail.body());
        assertTrue(detail.body().contains("Summer night"));
        assertTrue(detail.body().contains("\"communityAvailable\":true"));
        assertTrue(detail.body().contains("\"signedIn\":false"));
        assertFalse(detail.body().contains(owner.owner()));

        assertEquals(401, interact(http, "PUT", accountApi + "/like", guest.token(), true, "{\"active\":true}").statusCode());
        assertEquals(403, interact(http, "PUT", accountApi + "/like", listener.session(), false, "{\"active\":true}").statusCode());
        for (int attempt = 0; attempt < 2; attempt++) {
            var liked = interact(http, "PUT", accountApi + "/like", listener.session(), true, "{\"active\":true}");
            assertEquals(200, liked.statusCode(), liked.body());
            assertTrue(liked.body().contains("\"count\":1"));
        }
        assertTrue(interact(http, "GET", publicApi, listener.session(), true, null).body().contains("\"liked\":true"));
        assertTrue(interact(http, "PUT", accountApi + "/like", listener.session(), true, "{\"active\":false}").body().contains("\"count\":0"));

        String comment = accountApi + "/comments/" + UUID.randomUUID();
        for (int attempt = 0; attempt < 2; attempt++) {
            var result = interact(http, "PUT", comment, listener.session(), true, "{\"text\":\"I love the intro\"}");
            assertEquals(200, result.statusCode(), result.body());
            assertTrue(result.body().contains("\"count\":1"));
        }
        assertEquals(409, interact(http, "PUT", comment, listener.session(), true, "{\"text\":\"Different text\"}").statusCode());
        assertTrue(interact(http, "GET", publicApi + "/comments", null, true, null).body().contains("I love the intro"));
        assertEquals(200, interact(http, "DELETE", comment, owner.session(), true, null).statusCode());

        assertEquals(403, interact(http, "PUT", accountApi + "/description", listener.session(), true, "{\"text\":\"Public description\"}").statusCode());
        assertEquals(200, interact(http, "PUT", accountApi + "/description", owner.session(), true, "{\"text\":\"Public description\"}").statusCode());
        assertTrue(interact(http, "GET", publicApi, null, true, null).body().contains("Public description"));

        var newer = profiles.publishEverywhere(owner.owner(), owner.work(), asset, null, "New version", null).song();
        assertEquals(release.id(), newer.id());
        assertNotEquals(release.version(), newer.version());
        assertEquals(404, interact(http, "GET", publicApi, null, true, null).statusCode());
        assertEquals(404, interact(http, "PUT", accountApi + "/like", listener.session(), true, "{\"active\":true}").statusCode());
        var newerDetail = interact(http, "GET", "/api/public/discover/songs/" + newer.id() + "/" + newer.version(), null, true, null);
        assertEquals(200, newerDetail.statusCode(), newerDetail.body());
        assertFalse(newerDetail.body().contains("Public description"));
    }

    private HttpResponse<String> interact(HttpClient http, String method, String path, String cookie, boolean csrf, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (cookie != null) request.header("Cookie", "QF_SESSION=" + cookie + "; XSRF-TOKEN=discover-fixture");
        if (csrf) request.header("X-XSRF-TOKEN", "discover-fixture");
        if (body != null) request.header("Content-Type", "application/json");
        return http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> complete(HttpClient http, Song viewer, Song target, boolean csrf) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                + "/api/app/artist-profile/releases/" + target.work() + "/publication"))
                .header("Cookie", "QF_SESSION=" + viewer.session() + "; XSRF-TOKEN=publication-fixture")
                .POST(HttpRequest.BodyPublishers.noBody());
        if (csrf) request.header("X-XSRF-TOKEN", "publication-fixture");
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private record Song(String owner, String work, GenerationApi.Task task, String session) {}

    private Song generate(boolean publicly) {
        var session = identity.create();
        String owner = session.creatorId();
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
        return new Song(owner, work.id(), task, session.token());
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
