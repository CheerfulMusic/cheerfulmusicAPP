package com.qingfeng.music.server.artist;

import com.qingfeng.music.common.exception.DomainException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile({"mysql", "test"})
@ConditionalOnProperty(name={"qingfeng.generation.enabled", "qingfeng.generation.scheduler-enabled"}, havingValue="true")
final class SongPublicationWorker {
    private static final System.Logger LOG = System.getLogger(SongPublicationWorker.class.getName());
    private final ArtistProfileService profiles;

    SongPublicationWorker(ArtistProfileService profiles) {
        this.profiles = profiles;
    }

    @Scheduled(fixedDelayString = "${qingfeng.generation.publication-scan-ms:2000}")
    void publishReadySongs() {
        for (var job : profiles.readyPublications()) {
            try {
                profiles.finishPublication(job);
            } catch (RuntimeException failed) {
                profiles.publicationFailed(job, failed instanceof DomainException);
                LOG.log(System.Logger.Level.WARNING, "Automatic song publication failed for event {0}: {1}",
                        job.id(), failed.getClass().getSimpleName());
            }
        }
    }
}
