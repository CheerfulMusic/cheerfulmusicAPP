package com.qingfeng.music.server.hotsong;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.qingfeng.music.generation.application.AudioReferenceClips;
import com.qingfeng.music.generation.application.port.MusicProvider;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;

public final class OssAudioReferenceClips implements AudioReferenceClips {
    public static final String PREFIX = "qingfeng/generation-reference/";
    private final JdbcTemplate db;
    private final Map<String, Clip> approved;
    private final OSS oss;
    private final String bucket;
    private final Clock clock;
    private volatile Boolean schemaReady;

    public OssAudioReferenceClips(JdbcTemplate db, Map<String, Clip> approved, OSS oss, String bucket, Clock clock) {
        approved.forEach((id, clip) -> {
            if (!HotSongCreationReferences.trackIds().contains(id)
                || !clip.objectKey().matches(PREFIX + id + "/[a-f0-9]{12,64}\\.mp3")
                || !Double.isFinite(clip.weight()) || clip.weight() < 0 || clip.weight() > 1)
                throw new IllegalArgumentException("参考片段配置无效：" + id);
        });
        this.db = db;
        this.approved = Map.copyOf(approved);
        this.oss = oss;
        this.bucket = bucket;
        this.clock = clock;
    }

    @Override public Optional<Clip> selected(String trackId) {
        return trackId == null || oss == null ? Optional.empty() : Optional.ofNullable(approved.get(trackId)).filter(clip -> schemaAvailable());
    }

    @Override public Optional<Clip> frozen(String taskId) {
        if (!schemaAvailable()) return Optional.empty();
        return db.query("SELECT object_key,audio_weight FROM generation_audio_reference WHERE task_id=?",
            (row, i) -> new Clip(row.getString(1), row.getDouble(2)), taskId).stream().findFirst();
    }

    @Override public boolean schemaAvailable() {
        if (schemaReady == null) {
            schemaReady = Boolean.TRUE.equals(db.execute((ConnectionCallback<Boolean>) connection -> {
                try (var tables = connection.getMetaData().getTables(connection.getCatalog(), connection.getSchema(),
                    "generation_audio_reference", new String[]{"TABLE"})) {
                    return tables.next();
                }
            }));
        }
        return schemaReady;
    }

    @Override public void freeze(String taskId, Clip clip) {
        db.update("INSERT INTO generation_audio_reference(task_id,object_key,audio_weight) VALUES(?,?,?)",
            taskId, clip.objectKey(), clip.weight());
    }

    @Override public URI sign(Clip clip) {
        try {
            if (oss != null && clip.objectKey().matches(PREFIX + "[a-z0-9-]{1,64}/[a-f0-9]{12,64}\\.mp3")
                    && oss.doesObjectExist(bucket, clip.objectKey())) {
                var expiration = Date.from(clock.instant().plus(Duration.ofHours(1)));
                var signed = oss.generatePresignedUrl(bucket, clip.objectKey(), expiration, HttpMethod.GET).toURI();
                var host = signed.getHost();
                if ("https".equalsIgnoreCase(signed.getScheme()) && host != null
                        && host.matches("[a-z0-9-]+\\.oss-[a-z0-9-]+\\.aliyuncs\\.com") && !host.contains("-internal."))
                    return signed;
            }
        } catch (Exception ignored) {
            // Storage exceptions may contain signed URLs. Only record the stable error code.
        }
        throw new MusicProvider.NotSubmitted("AUDIO_REFERENCE_UNAVAILABLE");
    }
}
