package com.qingfeng.music.server.ai;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ObjectMetadata;
import com.qingfeng.music.common.exception.DomainException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Private transient JPEGs are shared by API and workers through the configured storage. */
public final class AiPhotoInputs {
    private static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    private static final String PREFIX = "qingfeng/ai-inputs/lyrics/";
    private final OSS oss;
    private final String bucket;
    private final Path directory;

    public AiPhotoInputs(OSS oss, String bucket, Path directory) {
        this.oss = oss;
        this.bucket = bucket;
        this.directory = directory.toAbsolutePath().normalize();
    }

    public List<String> save(List<byte[]> photos) {
        if (photos.size() > 5) throw new DomainException("INVALID_LYRICS_REQUEST", "最多上传 5 张照片。");
        var keys = new ArrayList<String>();
        try {
            for (byte[] bytes : photos) {
                if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES)
                    throw new DomainException("INVALID_LYRICS_REQUEST", "照片超出处理范围。");
                String key = UUID.randomUUID() + ".jpg";
                keys.add(key);
                if (oss == null) {
                    Files.createDirectories(directory);
                    Files.write(path(key), bytes, java.nio.file.StandardOpenOption.CREATE_NEW);
                } else {
                    var metadata = new ObjectMetadata();
                    metadata.setContentType("image/jpeg");
                    metadata.setContentLength(bytes.length);
                    try (var input = new ByteArrayInputStream(bytes)) {
                        oss.putObject(bucket, PREFIX + key, input, metadata);
                    }
                }
            }
            return List.copyOf(keys);
        } catch (IOException | RuntimeException error) {
            discard(keys);
            if (error instanceof DomainException domain) throw domain;
            throw new DomainException("LYRICS_PHOTO_STORAGE_UNAVAILABLE", "照片暂时无法保存，请稍后重试。");
        }
    }

    public List<byte[]> read(List<String> keys) {
        var photos = new ArrayList<byte[]>();
        try {
            for (String key : keys) {
                Path file = path(key);
                try (InputStream input = oss == null ? Files.newInputStream(file) : oss.getObject(bucket, PREFIX + key).getObjectContent()) {
                    byte[] bytes = input.readNBytes(MAX_IMAGE_BYTES + 1);
                    if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) throw new IOException("Unexpected photo size");
                    photos.add(bytes);
                }
            }
            return List.copyOf(photos);
        } catch (IOException | RuntimeException error) {
            throw new DomainException("LYRICS_PHOTO_STORAGE_UNAVAILABLE", "暂时无法读取本次创作的照片。");
        }
    }

    public void discard(List<String> keys) {
        for (String key : keys) {
            try {
                Path file = path(key);
                if (oss == null) Files.deleteIfExists(file); else oss.deleteObject(bucket, PREFIX + key);
            } catch (IOException | RuntimeException error) {
                System.getLogger(AiPhotoInputs.class.getName()).log(System.Logger.Level.WARNING,
                    "AI input cleanup failed: {0}", error.getClass().getSimpleName());
            }
        }
    }

    private Path path(String key) {
        if (!key.matches("[a-f0-9-]{36}\\.jpg")) throw new IllegalArgumentException("Invalid photo key");
        return directory.resolve(key);
    }
}
