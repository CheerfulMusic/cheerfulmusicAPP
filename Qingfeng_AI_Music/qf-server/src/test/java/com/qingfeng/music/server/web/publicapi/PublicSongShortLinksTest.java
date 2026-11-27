package com.qingfeng.music.server.web.publicapi;

import java.sql.DriverManager;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PublicSongShortLinksTest {
    @Test void distinguishesExactCaseAndLiteralUnderscoresInAnIgnoreCaseColumn() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:short_links;MODE=MySQL;IGNORECASE=TRUE")) {
            var db = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            db.execute("CREATE TABLE share_link(public_song_id VARCHAR(43) NOT NULL)");
            db.execute("CREATE INDEX idx_share_song_active ON share_link(public_song_id)");
            var links = new PublicSongShortLinks(db);
            String code = "a_" + "C".repeat(20);
            String id = code + "D".repeat(21);
            String sameIdDifferentCase = id.substring(0, 42) + "d";
            for (String candidate : new String[]{id, id, id.replace('_', 'x'), id.replace('a', 'A')}) {
                db.update("INSERT INTO share_link(public_song_id) VALUES(?)", candidate);
            }

            assertEquals(Optional.of(id), links.resolve(code));
            assertEquals(Optional.of(code), links.codeFor(id));
            assertEquals(Optional.empty(), links.resolve(code.replace('C', 'c')));
            assertEquals(Optional.empty(), links.resolve(code.replace('_', '%')));
            db.update("INSERT INTO share_link(public_song_id) VALUES(?)", sameIdDifferentCase);
            assertEquals(Optional.empty(), links.resolve(code));
            assertEquals(Optional.empty(), links.codeFor(id));
        }
    }
}
