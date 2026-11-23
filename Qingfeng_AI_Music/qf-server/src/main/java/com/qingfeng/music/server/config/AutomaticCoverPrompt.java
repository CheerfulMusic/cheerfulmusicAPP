package com.qingfeng.music.server.config;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

final class AutomaticCoverPrompt {
    private static final Pattern PRIVATE_DETAILS = Pattern.compile(
            "(?i)https?://\\S+|[\\w.+-]+@[\\w.-]+\\.[a-z]{2,}|(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern SECTION = Pattern.compile("^\\[([^\\]]+)]\\s*:?$|^【([^】]+)】\\s*[:：]?$");

    // Shared by one worker. Track only composition choices, never song text.
    private final EnumMap<Style, Integer> lastComposition = new EnumMap<>(Style.class);

    synchronized String create(String jobId, String title, String lyrics, String story, String style) {
        String safeTitle = clean(title, 100);
        String safeStyle = clean(style, 180);
        String lyricExcerpt = excerpt(lyrics);
        String theme = lyricExcerpt.isBlank() ? clean(story, 450) : lyricExcerpt;
        List<Match> styles = java.util.Arrays.stream(Style.values())
                .map(group -> group.findIn(safeStyle))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(Match::start).thenComparing(Comparator.comparingInt(Match::length).reversed()))
                .toList();
        Style primary = styles.isEmpty() ? Style.POP : styles.get(0).style();
        int count = primary.compositions.size();
        int composition = Math.floorMod(Objects.hash(jobId, safeTitle, theme), count);
        if (Objects.equals(lastComposition.get(primary), composition)) {
            composition = (composition + 1) % count;
        }
        lastComposition.put(primary, composition);

        String secondary = styles.size() < 2 ? "" :
                "\n混合风格可借用一个次风格特征（" + styles.get(1).style().label + "），仅影响光线、纹理或色彩其中一项。";
        return """
                创作一张 1:1 的纯画面唱片封面。将下方歌曲资料仅用于理解主题和情绪，从中选一个具体意象，转化为有辨识度的物件、场景或抽象形态。全图只有一个主视觉焦点。
                采用专业唱片摄影或艺术指导的质感。以材质细节、明暗层次和适量留白表现情绪，控制配色与高光，主体在小缩略图里仍然清楚。
                画面内无人物、脸、人体、人物剪影、歌名、艺人名、可读文字、字母、数字、Logo、水印或界面元素。包括招牌、唱片标签和器物表面在内，一律使用无字版本。
                """.stripTrailing()
                + "\n主风格（" + primary.label + "）：" + primary.direction
                + "\n本首构图：" + primary.compositions.get(composition)
                + subgenre(safeStyle, primary) + secondary
                + "\n歌曲资料（仅供理解，无需绘制任何字符）\n歌名：" + safeTitle
                + "\n曲风和情绪：" + safeStyle
                + (theme.isBlank() ? "" : (lyricExcerpt.isBlank() ? "\n创作描述：" : "\n歌词意象来源：") + theme);
    }

    private static String subgenre(String style, Style primary) {
        if (primary != Style.ELECTRONIC) return "";
        String electronic = style.toLowerCase(Locale.ROOT);
        if (electronic.contains("house")) return "\nHouse 可用暖调侧光或反光材质，保留律动感。";
        if (electronic.contains("techno")) return "\nTechno 可用冷调硬光和克制的空间结构。";
        if (electronic.contains("disco")) return "\nDisco 可用镜面反射与有节奏的斑驳光线。";
        return "";
    }

    private static String excerpt(String lyrics) {
        if (lyrics == null || lyrics.isBlank()) return "";
        var opening = new LinkedHashSet<String>();
        var chorus = new LinkedHashSet<String>();
        boolean inChorus = false;
        for (String raw : lyrics.lines().toList()) {
            String line = clean(raw, 200);
            if (line.isBlank()) continue;
            var marker = SECTION.matcher(line);
            if (marker.matches()) {
                String label = (marker.group(1) == null ? marker.group(2) : marker.group(1)).toLowerCase(Locale.ROOT);
                inChorus = label.contains("chorus") || label.contains("副歌");
                continue;
            }
            if (opening.size() < 4) opening.add(line);
            if (inChorus && chorus.size() < 4) chorus.add(line);
        }
        chorus.addAll(opening);
        return clean(String.join(" / ", chorus), 650);
    }

    private static String clean(String value, int limit) {
        if (value == null) return "";
        String cleaned = PRIVATE_DETAILS.matcher(value).replaceAll("")
                .replaceAll("[\\p{Cntrl}<>]+", " ").strip();
        return cleaned.substring(0, cleaned.offsetByCodePoints(0, Math.min(limit, cleaned.codePointCount(0, cleaned.length()))));
    }

    private record Match(Style style, int start, int length) {}

    private enum Style {
        ELECTRONIC("电子", "电子|电音|电气|合成器浪潮|迪斯科|electronic|electro|edm|house|techno|synth[ -]?wave|disco|trance|dubstep|drum[ &'-]+(?:and )?bass|garage",
                "精细的玻璃、金属、光束或细颗粒，根据主题决定冷暖，留出干净的暗部。",
                "微距静物，突出一个主题物件的材料和反光边缘。",
                "建筑广角，让所选场景的结构和一道光形成节奏。",
                "超现实静物，让一个与歌曲有关的实体悬浮或产生反常的倒影。",
                "抽象近景，把最重要的意象化成单一的流动形态，突出细颗粒。"),
        FOLK("民谣与乡村", "民谣|乡村|木吉他|indie[ -]folk|folk|country|acoustic|americana|bluegrass",
                "自然光、细胶片颗粒和真实的木材、织物或土地肌理。",
                "景深很浅的静物，用真实磨损表现故事。",
                "开阔的远景，让地形或建筑轮廓带出故事。",
                "透过窗框或门框取景，用内外光线表达距离。",
                "俯拍与主意象相关的一件旧物，用柔和的长影留白。"),
        TRADITIONAL("古风与国风", "古风|国风|中国风|古韵|古装|古筝|traditional chinese|chinese traditional|guofeng|gufeng",
                "用纸绢肌理、温润矿物色、山水留白或古典器物体现古意。",
                "远山疏景，让层次和空白共同构成重心。",
                "在自然微光下近摄一件与主题有关的器物。",
                "以前景帘、窗或叶片框住一处小景。",
                "提炼成疏密有致的水墨形态，保留真实纸绢纹理。"),
        ROCK("摇滚与独立", "摇滚|朋克|金属乐|独立音乐|rock|punk|metal|grunge|shoegaze|emo|indie",
                "侧光和粗粝的胶片或拼贴质感，让一个物件或空间有力量。",
                "斜向近景，用清晰轮廓和侧光表现物件。",
                "正面建筑远景，用结构、尺度和暗部表达张力。",
                "纸张、照片和真实材料构成的简洁拼贴。",
                "低机位看主题场景，让地表纹理带出运动感。"),
        JAZZ("爵士与灵魂乐", "爵士|蓝调|灵魂乐|波萨诺瓦|节奏布鲁斯|节奏蓝调|jazz|soul|r\\s*&\\s*b|rnb|blues|bossa(?: nova)?|gospel",
                "使用温润暗光和细腻材质，可选黄铜、丝绒、深木或哑光玻璃。",
                "在一小片光里用特写表现主题物件。",
                "留出阴影，用空旷的室内场景讲故事。",
                "用俯拍静物与柔和渐暗表现节奏。",
                "用绒面与玻璃等一组触感对照表现情绪。"),
        RAP("嘻哈与说唱", "嘻哈|说唱|饶舌|hip[ -]?hop|rap|trap|drill|boom[ -]?bap",
                "城市材质、硬闪或克制的剪纸构图，色调跟随主题。",
                "微距城市静物，突出磨痕和结构。",
                "低机位城市广角，突出空间和几何。",
                "两层纸张形态组成强对比拼贴。",
                "夜间侧光下的单一物件，背景收干净。"),
        AMBIENT("氛围与轻音乐", "轻音乐|纯音乐|器乐|白噪音|氛围|钢琴|弦乐|配乐|古典|ambient|lo[ -]?fi|instrumental|piano|classical|soundtrack|new age",
                "开放空间、低对比和细腻光线，色调与音乐氛围一致。",
                "让雾或空气层次勾勒主题场景。",
                "极简特写，用清晰轮廓表现单一物件。",
                "用视线平齐的宽阔场景与柔光留白。",
                "从主题抽取一个形状，由缓慢变化的色调衬托。"),
        POP("流行", "流行|港风|情歌|拉丁|童趣|儿歌|校园|indie[ -]pop|pop|ballad|latin",
                "电影感场景或细腻静物，用明确主色与自然的高光表达情绪。",
                "专注一个物件的近景，突出微小细节。",
                "用开阔风景建立情绪，给焦点留出空间。",
                "选一个视角拍无人的室内场景，用单一光源塑造质感。",
                "用俯拍或倒影呈现主题物件，做出简洁的几何构图。");

        final String label;
        final Pattern aliases;
        final String direction;
        final List<String> compositions;

        Style(String label, String aliases, String direction, String... compositions) {
            this.label = label;
            this.aliases = Pattern.compile("(?<![a-z])(?:" + aliases + ")(?![a-z])", Pattern.CASE_INSENSITIVE);
            this.direction = direction;
            this.compositions = List.of(compositions);
        }

        Match findIn(String style) {
            var match = aliases.matcher(style);
            return match.find() ? new Match(this, match.start(), match.end() - match.start()) : null;
        }
    }
}
