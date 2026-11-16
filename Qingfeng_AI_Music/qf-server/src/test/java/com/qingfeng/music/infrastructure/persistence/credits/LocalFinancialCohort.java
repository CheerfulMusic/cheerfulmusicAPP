package com.qingfeng.music.infrastructure.persistence.credits;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Test-only binding to a verified fresh MySQL instance or the pinned legacy cohort. */
public final class LocalFinancialCohort {
    private static final UUID LEGACY_UUID = UUID.fromString("05c27d3c-dda3-11f1-aba0-84a938421519");
    private static final UUID PRODUCTION_UUID = UUID.fromString("f71f7437-d6a8-11f1-baa2-00163e1ce234");
    private final UUID uuid;
    private final String dataDirectory;

    private LocalFinancialCohort(UUID uuid, String dataDirectory) {
        this.uuid = uuid;
        this.dataDirectory = dataDirectory;
    }

    public static LocalFinancialCohort assertServer(String expectedUuid, String actualUuid, String actualDataDirectory) {
        assertEquals(expectedUuid, actualUuid);
        UUID uuid = UUID.fromString(expectedUuid);
        assertEquals(uuid.toString(), actualUuid);
        assertNotEquals(PRODUCTION_UUID, uuid);
        String dataDirectory = actualDataDirectory.replace('\\', '/').toLowerCase(Locale.ROOT);
        String configured = System.getenv("QF_ISOLATED_COHORT_DATADIR");
        if (configured == null) {
            assertEquals(LEGACY_UUID, uuid);
            assertEquals("r:/data/", dataDirectory);
        } else {
            assertNotEquals(LEGACY_UUID, uuid);
            assertFreshDirectory(configured, actualDataDirectory);
        }
        return new LocalFinancialCohort(uuid, dataDirectory);
    }

    private static void assertFreshDirectory(String configured, String actual) {
        try {
            Path requested = Path.of(configured);
            assertTrue(requested.isAbsolute(), "The isolated data directory must be absolute");
            Path expected = requested.toRealPath();
            assertEquals(expected, Path.of(actual).toRealPath());
            for (Path workspace = Path.of("").toRealPath(); workspace != null; workspace = workspace.getParent()) {
                Path instances = workspace.resolve(".cache/release-gate-cohorts/instances");
                if (!Files.isDirectory(instances)) continue;
                Path anchor = instances.toRealPath();
                if (!anchor.equals(instances) || !expected.startsWith(anchor)) continue;
                Path relative = anchor.relativize(expected);
                if (relative.getNameCount() == 2 && relative.getFileName().equals(Path.of("financial"))) return;
            }
            throw new IllegalStateException("MySQL data directory is outside the workspace financial cohort");
        } catch (IOException e) {
            throw new IllegalStateException("Cannot verify the isolated MySQL data directory", e);
        }
    }

    public static CreditHistoricalCutover historicalCutover(JdbcTemplate db, TransactionTemplate transactions,
                                                              String database, String expectedUuid) {
        var cohort = db.queryForObject("SELECT VERSION(),@@server_uuid,@@datadir,@@port,@@bind_address,DATABASE(),CURRENT_ROLE()", (rs, row) -> {
            assertEquals("8.0.46", rs.getString(1));
            assertEquals(23316, rs.getInt(4));
            assertEquals("127.0.0.1", rs.getString(5));
            assertEquals(database, rs.getString(6));
            assertEquals("NONE", rs.getString(7));
            return assertServer(expectedUuid, rs.getString(2), rs.getString(3));
        });
        assertNotNull(cohort);
        assertEquals(Set.of(database, "information_schema", "performance_schema"),
            new HashSet<>(db.queryForList("SELECT SCHEMA_NAME FROM information_schema.SCHEMATA", String.class)));
        var grants = db.queryForList("SHOW GRANTS", String.class);
        assertEquals(2, grants.size());
        for (String grant : grants) {
            assertTrue(grant.startsWith("GRANT USAGE ON *.*") || grant.contains(" ON `" + database + "`.* TO "));
            assertFalse(grant.contains("WITH GRANT OPTION"));
        }
        return new CreditHistoricalCutover(db, transactions, cohort.uuid, cohort.dataDirectory);
    }
}
