package com.qingfeng.music.server;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.infrastructure.persistence.credits.JdbcLyricFreeAllowance;
import java.time.Clock;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

class JdbcLyricFreeAllowanceTest {
    private JdbcTemplate jdbc;
    private JdbcLyricFreeAllowance allowance;
    private String owner;
    private String work;

    @BeforeEach void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:lyric_grants_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE music_work(id VARCHAR(36) PRIMARY KEY,owner_id VARCHAR(36),deleted_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE lyrics_composition_request(work_id VARCHAR(36),request_key VARCHAR(36),status VARCHAR(16))");
        jdbc.execute("CREATE TABLE generation_task(id VARCHAR(36) PRIMARY KEY,work_id VARCHAR(36),status VARCHAR(32))");
        jdbc.execute("CREATE TABLE model_usage_reservation(kind VARCHAR(16),work_id VARCHAR(36),request_key VARCHAR(36),owner_id VARCHAR(36),usage_day DATE,created_at TIMESTAMP,PRIMARY KEY(kind,work_id,request_key))");
        allowance = new JdbcLyricFreeAllowance(jdbc, Clock.systemUTC(), ZoneId.of("Asia/Shanghai"));
        owner = UUID.randomUUID().toString(); work = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO music_work(id,owner_id) VALUES(?,?)", work, owner);
    }

    @Test void firstLyricIsFreeThenEachSuccessfulMusicTaskGrantsOneMore() {
        String first = UUID.randomUUID().toString(), second = UUID.randomUUID().toString();
        assertTrue(allowance.freeAvailable(owner, work));
        assertEquals(0L, count("SELECT COUNT(*) FROM model_usage_reservation")); // status lookup is read-only
        assertTrue(allowance.reserve(owner, work, first));
        assertTrue(allowance.reserve(owner, work, first)); // request replay does not take another grant
        assertFalse(allowance.freeAvailable(owner, work));
        assertFalse(allowance.reserve(owner, work, second));

        String task = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO generation_task(id,work_id,status) VALUES(?,?,?)", task, work, "RUNNING");
        assertThrows(DomainException.class, () -> allowance.grantMusic(owner, work, task));
        jdbc.update("UPDATE generation_task SET status='SUCCEEDED' WHERE id=?", task);
        allowance.grantMusic(owner, work, task);
        allowance.grantMusic(owner, work, task); // duplicate success callback cannot grant twice
        assertTrue(allowance.freeAvailable(owner, work));
        assertTrue(allowance.reserve(owner, work, second));
        assertFalse(allowance.freeAvailable(owner, work));
        assertEquals(2L, count("SELECT COUNT(*) FROM model_usage_reservation WHERE kind='LYRIC_GRANT'"));
        assertEquals(2L, count("SELECT COUNT(*) FROM model_usage_reservation WHERE kind='LYRIC_REDEEM'"));
    }

    @Test void definiteFailureReturnsTheGrantButUnknownResultKeepsItReserved() {
        String first = UUID.randomUUID().toString();
        assertTrue(allowance.reserve(owner, work, first));
        assertFalse(allowance.freeAvailable(owner, work));
        // UNKNOWN does not call release; retry cannot take another free use.
        assertFalse(allowance.reserve(owner, work, UUID.randomUUID().toString()));
        allowance.release(work, first);
        assertTrue(allowance.freeAvailable(owner, work));
        assertTrue(allowance.reserve(owner, work, UUID.randomUUID().toString()));
    }

    @Test void olderWorkWithPriorLyricRequestDoesNotReceiveASecondInitialGrant() {
        jdbc.update("INSERT INTO lyrics_composition_request(work_id,request_key,status) VALUES(?,?,?)",
            work, UUID.randomUUID().toString(), "SUCCEEDED");
        assertFalse(allowance.freeAvailable(owner, work));
        assertFalse(allowance.reserve(owner, work, UUID.randomUUID().toString()));
        assertEquals(0L, count("SELECT COUNT(*) FROM model_usage_reservation"));
    }

    @Test void cannotReadOrConsumeAnotherCreatorsWork() {
        String other = UUID.randomUUID().toString();
        assertThrows(DomainException.class, () -> allowance.freeAvailable(other, work));
        assertThrows(DomainException.class, () -> allowance.reserve(other, work, UUID.randomUUID().toString()));
        assertEquals(0L, count("SELECT COUNT(*) FROM model_usage_reservation"));
    }

    private long count(String sql) { return jdbc.queryForObject(sql, Long.class); }
}
