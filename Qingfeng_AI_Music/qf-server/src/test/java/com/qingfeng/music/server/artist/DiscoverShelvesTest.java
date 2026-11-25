package com.qingfeng.music.server.artist;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class DiscoverShelvesTest {
    @Test
    void countsOnlyTodayAndShowsOnlyApprovedPublicVersions() {
        var source = discover44DataSource();
        var db = new JdbcTemplate(source);
        new ResourceDatabasePopulator(new ClassPathResource("db/test/audio_version_annotations.sql")).execute(source);
        // H2 exercises the service queries; MySQL V50 resolves the production column collation.
        db.execute("""
                CREATE TABLE discover_daily_play (
                    play_day DATE NOT NULL,
                    release_id VARCHAR(36) NOT NULL,
                    release_version BIGINT NOT NULL,
                    plays BIGINT NOT NULL,
                    PRIMARY KEY (play_day, release_id, release_version)
                )
                """);
        db.execute("""
                CREATE TABLE discover_vocal_approval (
                    release_id VARCHAR(36) NOT NULL,
                    release_version BIGINT NOT NULL,
                    PRIMARY KEY (release_id, release_version)
                )
                """);
        db.update("INSERT INTO creator VALUES ('owner',CURRENT_TIMESTAMP)");
        db.update("INSERT INTO artist_profile(creator_id,public_id,stage_name,tagline,published) VALUES ('owner','artist','歌手','',TRUE)");
        addSong(db, "jazz", "爵士 / Soul");
        addSong(db, "pop", "欧美流行");
        var profiles = new ArtistProfileService(db, null, null, null, null, null, null);

        var yesterday = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1);
        db.update("INSERT INTO discover_daily_play VALUES (?,'pop',1,99)", yesterday);
        db.update("INSERT INTO hot_song_metric(track_id,plays) VALUES ('discover:pop',99)");
        profiles.recordDiscoverPlay("jazz", 1);
        profiles.recordDiscoverPlay("jazz", 1);
        profiles.recordDiscoverPlay("pop", 1);
        profiles.setDiscoverVocal("jazz", 1, true);
        profiles.setDiscoverVocal("jazz", 1, true);

        var shelves = profiles.discoverShelves();
        assertTrue(shelves.listeningDataAvailable());
        assertEquals(List.of("jazz", "pop"), shelves.today().stream().map(row -> row.song().id()).toList());
        assertEquals(List.of(2L, 1L), shelves.today().stream().map(ArtistProfileService.DiscoverSong::plays).toList());
        assertEquals(List.of("jazz"), shelves.vocal().stream().map(row -> row.song().id()).toList());
        assertEquals("jazz", shelves.genres().get("jazz-soul").get(0).song().id());
        assertEquals("pop", shelves.genres().get("global-pop").get(0).song().id());
        assertEquals(100L, profiles.discover(false, true, "pop", "", 0).songs().get(0).plays());

        profiles.setDiscoverVocal("jazz", 1, false);
        assertTrue(profiles.discoverShelves().vocal().isEmpty());
        profiles.setDiscoverVocal("jazz", 1, true);
        db.update("UPDATE artist_song_release SET version=2 WHERE id='jazz'");
        assertTrue(profiles.discoverShelves().vocal().isEmpty());
        assertEquals(List.of("pop"), profiles.discoverShelves().today().stream().map(row -> row.song().id()).toList());
        db.update("INSERT INTO media_asset_annotation(asset_id,deleted_at) VALUES ('pop',CURRENT_TIMESTAMP)");
        assertTrue(profiles.discoverShelves().today().isEmpty());
        assertTrue(profiles.discoverShelves().genres().get("global-pop").isEmpty());
    }

    @Test
    void bareCore44KeepsItsOriginalFeedButOnlyVerifiedRecordingsCanEnterSearch() {
        var source = discover44DataSource();
        var db = new JdbcTemplate(source);
        db.execute("CREATE TABLE flyway_schema_history(installed_rank INT, version VARCHAR(20), script VARCHAR(200), success BOOLEAN)");
        db.update("INSERT INTO flyway_schema_history VALUES (44,'44','V44__discover_song_publication.sql',TRUE)");
        db.update("INSERT INTO creator VALUES ('owner',CURRENT_TIMESTAMP)");
        db.update("INSERT INTO artist_profile(creator_id,public_id,stage_name,tagline,published) VALUES ('owner','artist','歌手','',TRUE)");
        addSong(db, "legacy", "folk");
        var profiles = new ArtistProfileService(db, null, null, null, null, null, null);
        assertEquals(1, profiles.discover(false, false, "", "", 0).songs().size());
        assertTrue(profiles.latestCatalogSongs(false, 24).isEmpty());
        assertTrue(profiles.catalogSong("legacy", 1).isEmpty());
        db.update("INSERT INTO flyway_schema_history VALUES (56,'56','V56__discover_song_community.sql',TRUE)");
        assertThrows(org.springframework.jdbc.BadSqlGrammarException.class,
                () -> profiles.discover(false, false, "", "", 0));
        new ResourceDatabasePopulator(new ClassPathResource("db/test/audio_version_annotations.sql")).execute(source);
        assertTrue(profiles.catalogSong("legacy", 1).isPresent());
        db.update("INSERT INTO media_asset_annotation(asset_id,deleted_at) VALUES ('legacy',CURRENT_TIMESTAMP)");
        assertTrue(profiles.catalogSong("legacy", 1).isEmpty());
        assertTrue(profiles.discover(false, false, "", "", 0).songs().isEmpty());
    }

    private static JdbcDataSource discover44DataSource() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:discover-" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        migrate(source, "V1__creator_and_work.sql", "V2__lyrics_media_and_sharing.sql",
                "V26__hot_song_engagement.sql", "V27__work_soft_delete.sql");
        new JdbcTemplate(source).execute("CREATE TABLE cover_asset (id VARCHAR(36) PRIMARY KEY)");
        migrate(source, "V40__artist_profiles_and_releases.sql", "V44__discover_song_publication.sql");
        return source;
    }

    private static void migrate(JdbcDataSource source, String... names) {
        var scripts = new ResourceDatabasePopulator();
        for (String name : names) scripts.addScript(new ClassPathResource("db/migration/" + name));
        scripts.execute(source);
    }

    private static void addSong(JdbcTemplate db, String id, String style) {
        db.update("""
                INSERT INTO music_work(id,owner_id,recipient,occasion,story,message,style,title,version,created_at,updated_at)
                VALUES (?,'owner','','','','',?,?,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, id, style, id);
        db.update("""
                INSERT INTO media_asset(id,work_id,storage_key,origin,content_type,byte_size,sha256,created_at)
                VALUES (?,?,'audio','TEST','audio/wav',1,'sha',CURRENT_TIMESTAMP)
                """, id, id);
        db.update("""
                INSERT INTO artist_song_release(id,creator_id,work_id,asset_id,title,version,show_on_profile,published_at,discover_published_at)
                VALUES (?,'owner',?,?,?,1,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, id, id, id, id);
    }
}
