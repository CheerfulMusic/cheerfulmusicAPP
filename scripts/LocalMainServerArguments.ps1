function Get-LocalMainServerArguments([string]$runtimeJar, [string]$privateUri, [switch]$Development) {
    @(
        '-Xms64m', '-Xmx512m'
        '-jar', $runtimeJar
        '--spring.profiles.active=mysql', '--spring.profiles.include='
        '--spring.config.location=classpath:/', "--spring.config.additional-location=$privateUri"
        '--server.address=127.0.0.1', '--server.port=8080'
        '--spring.flyway.enabled=false', '--spring.sql.init.mode=never'
        '--qingfeng.quick.scheduler-enabled=false', '--qingfeng.quick.accept-enabled=false'
        '--qingfeng.lyrics.preparation.enabled=false', '--qingfeng.lyrics.preparation.scheduler-enabled=false'
        '--qingfeng.generation.scheduler-enabled=false', '--qingfeng.generation.vocal-adjustment-worker-enabled=false'
        '--qingfeng.cover.ai.scheduler-enabled=false', '--qingfeng.avatar.ai.scheduler-enabled=false'
        '--qingfeng.video.enabled=false', '--qingfeng.events.dispatcher-enabled=false'
        '--qingfeng.events.rabbit.enabled=false', '--qingfeng.events.rabbit.consumer-enabled=false'
        '--qingfeng.payment.inbox.scheduler-enabled=false', '--qingfeng.analytics.retention.enabled=false'
        '--qingfeng.analytics.retention.scheduler-enabled=false', '--qingfeng.agent.admission.recovery-enabled=false'
        '--qingfeng.agent.retrieval.enabled=false', '--qingfeng.agent.retrieval.scheduler-enabled=false'
        '--qingfeng.agent.legacy-pending.enabled=false', '--qingfeng.payment-demo.enabled=false'
        '--qingfeng.payments.enabled=false', '--qingfeng.paypal.enabled=false'
        '--qingfeng.paypal.live-enabled=false', '--qingfeng.paypal.reconciliation.enabled=false'
        '--qingfeng.auth.sms-bridge.enabled=false'
    )
    if ($Development) {
        '--qingfeng.scheduling.enabled=false'
        '--qingfeng.cover.automatic.scheduler-enabled=false'
        '--qingfeng.ai.capacity.enabled=false'
        '--qingfeng.providers.ark.qwen-timeout-fallback-enabled=false'
    }
}
