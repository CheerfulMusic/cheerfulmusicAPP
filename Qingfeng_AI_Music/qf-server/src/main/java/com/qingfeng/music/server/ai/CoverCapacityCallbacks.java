package com.qingfeng.music.server.ai;

import com.qingfeng.music.infrastructure.persistence.ai.JdbcAiWorkQueue;
import com.qingfeng.music.infrastructure.persistence.ai.JdbcAiWorkQueue.Lane;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@Profile({"mysql", "test"})
@ConditionalOnProperty(name = "qingfeng.ai.capacity.enabled", havingValue = "true")
@ConditionalOnProperty(name = "qingfeng.ai.capacity.cover.callback-base")
public final class CoverCapacityCallbacks {
    public static final String PREFIX = "/api/public/ai/cover/notice/";
    public static final String PATH = PREFIX + "{jobId}";
    private final URI origin;
    private final SecretKeySpec signingKey;
    private final JdbcAiWorkQueue queue;

    public CoverCapacityCallbacks(Environment environment, JdbcAiWorkQueue queue) {
        origin = URI.create(environment.getRequiredProperty("qingfeng.ai.capacity.cover.callback-base"));
        if (!"https".equalsIgnoreCase(origin.getScheme()) || origin.getHost() == null || origin.getUserInfo() != null
                || origin.getQuery() != null || origin.getFragment() != null || !java.util.Set.of("", "/").contains(origin.getPath()))
            throw new IllegalArgumentException("A public HTTPS origin is required for cover callbacks");
        byte[] secret = environment.getRequiredProperty("qingfeng.ai.capacity.cover.callback-secret").getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) throw new IllegalArgumentException("Cover callback secret must be at least 32 bytes");
        signingKey = new SecretKeySpec(secret, "HmacSHA256");
        this.queue = queue;
    }

    public URI url(String work, String requestKey) {
        String id = JdbcAiWorkQueue.identity(Lane.COVER, work, requestKey);
        return origin.resolve(PREFIX + id + "?signature=" + HexFormat.of().formatHex(signature(id)));
    }

    public boolean nudge(String id, String proof) {
        if (!id.matches("[0-9a-f]{64}") || proof == null || !proof.matches("[0-9a-f]{64}")
                || !MessageDigest.isEqual(signature(id), HexFormat.of().parseHex(proof))) return false;
        // The notification is only a hint. Workers still query the provider for the recorded task ID.
        queue.nudgeCoverPoll(id);
        return true;
    }

    private byte[] signature(String id) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(signingKey);
            return mac.doFinal(("cover-poll:" + id).getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot authenticate cover notification", e);
        }
    }
}
