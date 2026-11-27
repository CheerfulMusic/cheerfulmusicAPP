package com.qingfeng.music.server.web.publicapi;

import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile({"mysql","test"})
public final class PublicSongShortLinks {
    private static final int SHORT_CODE_LENGTH = 22;
    private static final Pattern SHORT_CODE = Pattern.compile("[A-Za-z0-9_-]{" + SHORT_CODE_LENGTH + "}");
    private static final Pattern PUBLIC_ID = Pattern.compile("[A-Za-z0-9_-]{43}");

    private final JdbcTemplate db;

    public PublicSongShortLinks(JdbcTemplate db) {
        this.db = db;
    }

    public Optional<String> codeFor(String publicId) {
        if (!PUBLIC_ID.matcher(publicId).matches()) return Optional.empty();
        String code = publicId.substring(0, SHORT_CODE_LENGTH);
        return resolve(code).filter(publicId::equals).map(ignored -> code);
    }

    Optional<String> resolve(String code) {
        if (!SHORT_CODE.matcher(code).matches()) return Optional.empty();
        String prefix = code.replace("_", "!_") + "%";
        // The indexed LIKE narrows the candidates. Java compares exact case even on case-insensitive databases.
        // Check every historical grant so a code cannot move to another song after expiry or revocation.
        return db.query("""
                SELECT public_song_id FROM share_link WHERE public_song_id LIKE ? ESCAPE '!'
                """, rows -> {
                    String match = null;
                    while (rows.next()) {
                        String candidate = rows.getString(1);
                        if (!candidate.startsWith(code)) continue;
                        if (!PUBLIC_ID.matcher(candidate).matches() || (match != null && !match.equals(candidate))) {
                            return Optional.empty();
                        }
                        match = candidate;
                    }
                    return Optional.ofNullable(match);
                }, prefix);
    }
}
