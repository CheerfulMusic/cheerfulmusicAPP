package com.qingfeng.music.server.config;

import com.qingfeng.music.infrastructure.provider.QwenInspirationRewriter;
import org.springframework.core.env.Environment;

/**
 * 千问文本模型的共享配置：AI 改写、歌词备选、Agent 对话与参考分析共用。
 * 默认千问AI平台（qianwenaiapi.com）的千问 Flash「qwen3.8-flash」，走 OpenAI 兼容
 * chat/completions；base-url 必须与签发该 key 的平台配套，不能混用阿里云百炼官方域名。
 *
 * <p>密钥绝不写入仓库：优先服务器私有配置 {@code qingfeng.providers.qwen.api-key}，
 * 其次环境变量 {@code aliyunqingfengKEY}，最后通用 {@code DASHSCOPE_API_KEY}。
 * 所有千问调用都从这里读取相同的密钥、模型和端点，避免配置漂移。
 */
final class RewriteProviderSettings {

    /** 只暴露「是否配置了密钥」的布尔值，避免配置对象携带密钥。 */
    record Settings(String model, String endpoint, boolean keyConfigured) {}

    private RewriteProviderSettings() {}

    static Settings settings(Environment env) {
        return new Settings(model(env), endpoint(env), !key(env).isBlank());
    }

    static QwenInspirationRewriter writer(Environment env) {
        return new QwenInspirationRewriter(key(env), model(env), endpoint(env));
    }

    static String key(Environment env) {
        String configured = env.getProperty("qingfeng.providers.qwen.api-key", "");
        if (!configured.isBlank()) return configured.trim();
        String fromEnv = System.getenv("aliyunqingfengKEY");
        if (fromEnv != null && !fromEnv.isBlank()) return fromEnv.trim();
        return env.getProperty("DASHSCOPE_API_KEY", "").trim();
    }

    static String model(Environment env) {
        String value = env.getProperty("qingfeng.providers.qwen.model", QwenInspirationRewriter.QWEN_MODEL);
        return value == null || value.isBlank() ? QwenInspirationRewriter.QWEN_MODEL : value.trim();
    }

    static String endpoint(Environment env) {
        String value = env.getProperty("qingfeng.providers.qwen.base-url", QwenInspirationRewriter.QWEN_ENDPOINT);
        String url = (value == null || value.isBlank() ? QwenInspirationRewriter.QWEN_ENDPOINT : value.trim())
            .replaceAll("/+$", "");
        // 允许只配到 .../compatible-mode/v1，自动补齐 chat/completions 路径。
        return url.endsWith("/chat/completions") ? url : url + "/chat/completions";
    }
}
