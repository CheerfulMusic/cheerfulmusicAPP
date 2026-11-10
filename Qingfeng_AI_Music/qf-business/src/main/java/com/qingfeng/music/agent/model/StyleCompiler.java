package com.qingfeng.music.agent.model;

/**
 * 结构化参数的风格编译：与前端 src/modules/agent/model/style-compiler.ts 共用「 | 」分隔符。
 *
 * 基础风格（如「温暖民谣」）仍可命中站内预设词典翻译成英文提示词；
 * 补充描述（BPM、配器、演唱语言、段落）不能伪装成 Kie 原生字段，只能追加在 style 文本末尾。
 * 合并必须幂等，并在超长时从尾部逐项丢弃补充描述，绝不截断用户的基础风格。
 */
public final class StyleCompiler {
    public static final String MARK = " | ";
    private StyleCompiler() {}

    public record Split(String base, String tail) {}

    public static Split split(String style) {
        String value = style == null ? "" : style.trim();
        int index = value.lastIndexOf(MARK);
        if (index < 0) return new Split(value, "");
        return new Split(value.substring(0, index).trim(), value.substring(index + MARK.length()).trim());
    }

    /** 供只需要展示基础风格的场景使用。 */
    public static String base(String style) { return split(style).base(); }

    /** 合并结果：style 是最终文本，dropped 列出因长度预算被丢掉的补充描述项。 */
    public record Merged(String style, java.util.List<String> dropped) {}

    public static String merge(String base, String tail, int limit) {
        return mergeWithDropped(base, tail, limit).style();
    }

    /**
     * 与前端 composeStyleDetailed 同规则：合并基础风格与补充描述，
     * 超长时从尾部逐项丢弃补充描述，并明确报告丢了哪些，绝不截断基础风格。
     */
    public static Merged mergeWithDropped(String base, String tail, int limit) {
        String cleanBase = base == null ? "" : base.trim();
        String cleanTail = tail == null ? "" : tail.trim();
        if (cleanTail.isEmpty()) return new Merged(cleanBase, java.util.List.of());
        if (cleanBase.isEmpty()) {
            String style = clamp(cleanTail, limit);
            return style.equals(cleanTail)
                ? new Merged(style, java.util.List.of())
                : new Merged(style, java.util.List.of(cleanTail.substring(Math.min(style.length(), cleanTail.length())).trim()));
        }
        if (cleanBase.endsWith(MARK + cleanTail)) return new Merged(cleanBase, java.util.List.of());
        String[] segments = cleanTail.split(", ");
        for (int count = segments.length; count > 0; count--) {
            StringBuilder candidate = new StringBuilder(cleanBase).append(MARK);
            for (int index = 0; index < count; index++) {
                if (index > 0) candidate.append(", ");
                candidate.append(segments[index]);
            }
            if (candidate.length() <= limit) {
                return new Merged(candidate.toString(), java.util.List.of(segments).subList(count, segments.length));
            }
        }
        return new Merged(cleanBase, java.util.List.of(segments));
    }

    private static String clamp(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, Math.max(0, limit)).trim();
    }
}
