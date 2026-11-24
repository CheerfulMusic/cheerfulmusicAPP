package com.qingfeng.music.server.mv;

import com.qingfeng.music.conversation.application.ConversationService;
import com.qingfeng.music.credits.CreditsLedger;
import com.qingfeng.music.media.api.MediaApi;
import com.qingfeng.music.work.api.WorkApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods=false)
@Profile({"mysql","test"})
@ConditionalOnProperty(name="qingfeng.video.enabled",havingValue="false",matchIfMissing=true)
public class DisabledVideoConfiguration {
    @Bean AgentVideoActions previewVideoActions(JdbcTemplate db, PlatformTransactionManager manager,
            WorkApi works, MediaApi media, CreditsLedger credits, AudioDuration duration, ConversationService conversations) {
        var preview = new MusicVideoService(db, new TransactionTemplate(manager), works, media, null, credits, duration);
        return new AgentVideoActions(preview, conversations, true);
    }
}
