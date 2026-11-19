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
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:discover-" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        var db = new JdbcTemplate(source);
        migrate(source, "V1__creator_and_work.sql", "V2__lyrics_media_and_sharing.sql",
                "V26__hot_song_engagement.sql", "V27__work_soft_delete.sql");
        db.execute("CREATE TABLE cover_asset (id VARCHAR(36) PRIMARY KEY)");
        migrate(source, "V40__artist_profiles_and_releases.sql", "V44__discover_song_publication.sql",
                "V50__discover_daily_plays_and_vocal_approval.sql");
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
