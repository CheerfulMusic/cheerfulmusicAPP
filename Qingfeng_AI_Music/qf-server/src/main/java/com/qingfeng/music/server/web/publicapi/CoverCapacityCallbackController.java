package com.qingfeng.music.server.web.publicapi;

import com.qingfeng.music.server.ai.CoverCapacityCallbacks;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile({"mysql", "test"})
@ConditionalOnProperty(name = "qingfeng.ai.capacity.enabled", havingValue = "true")
@ConditionalOnProperty(name = "qingfeng.ai.capacity.cover.callback-base")
public final class CoverCapacityCallbackController {
    private final CoverCapacityCallbacks callbacks;

    public CoverCapacityCallbackController(CoverCapacityCallbacks callbacks) {
        this.callbacks = callbacks;
    }

    @PostMapping(CoverCapacityCallbacks.PATH)
    public ResponseEntity<Void> notify(@PathVariable String jobId, @RequestParam(required = false) String signature) {
        if (!callbacks.nudge(jobId, signature)) return ResponseEntity.status(403).build();
        return ResponseEntity.noContent().build();
    }
}
