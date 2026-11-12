package com.qingfeng.music.server.config;

import com.qingfeng.music.infrastructure.provider.DeepSeekInspirationRewriter;
import org.springframework.core.env.Environment;

/**
 * AI 改写（灵感改写 / 歌词行改写 / 音乐要求精简）专用模型配置。
 * 默认千问AI平台（qianwenaiapi.com）的千问 Flash「qwen3.8-flash」，走 OpenAI 兼容
 * chat/completions；base-url 必须与签发该 key 的平台配套，不能混用阿里云百炼官方域名。
 *
 * <p>密钥绝不写入仓库：优先服务器私有配置 {@code qingfeng.providers.qwen.api-key}，
 * 其次环境变量 {@code aliyunqingfengKEY}，最后通用 {@code DASHSCOPE_API_KEY}。
 * 三处调用方（LyricsCompositionConfiguration 两处、MusicSettingsConfiguration 一处）
 * 都通过这里构造，避免配置漂移。
 *
 * <p>注意：歌词生成主链路用的 {@code qingfeng.providers.deepseek.*} 保持不变，
 * 本类只影响 AI 改写，两者互不影响。
 */
final class RewriteProviderSettings {

    /** 只暴露「是否配置了密钥」的布尔值，避免配置对象携带密钥。 */
    record Settings(String model, String endpoint, boolean keyConfigured) {}

    private RewriteProviderSettings() {}

    static Settings settings(Environment env) {
        return new Settings(model(env), endpoint(env), !key(env).isBlank());
    }

    static DeepSeekInspirationRewriter writer(Environment env) {
        return new DeepSeekInspirationRewriter(key(env), model(env), endpoint(env));
    }

    private static String key(Environment env) {
        String configured = env.getProperty("qingfeng.providers.qwen.api-key", "");
        if (!configured.isBlank()) return configured.trim();
        String fromEnv = System.getenv("aliyunqingfengKEY");
        if (fromEnv != null && !fromEnv.isBlank()) return fromEnv.trim();
        return env.getProperty("DASHSCOPE_API_KEY", "").trim();
    }

    private static String model(Environment env) {
        String value = env.getProperty("qingfeng.providers.qwen.model", DeepSeekInspirationRewriter.QWEN_MODEL);
        return value == null || value.isBlank() ? DeepSeekInspirationRewriter.QWEN_MODEL : value.trim();
    }

    private static String endpoint(Environment env) {
        String value = env.getProperty("qingfeng.providers.qwen.base-url", DeepSeekInspirationRewriter.QWEN_ENDPOINT);
        String url = (value == null || value.isBlank() ? DeepSeekInspirationRewriter.QWEN_ENDPOINT : value.trim())
            .replaceAll("/+$", "");
        // 允许只配到 .../compatible-mode/v1，自动补齐 chat/completions 路径。
        return url.endsWith("/chat/completions") ? url : url + "/chat/completions";
    }
}
