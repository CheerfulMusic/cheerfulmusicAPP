package com.qingfeng.music.infrastructure.provider;

import java.util.Map;

/** 站内预设的编曲词典。只展开完整匹配的预设，自定义描述保留用户原意。 */
final class MusicStylePrompt {
    private MusicStylePrompt() {}
    private static final Map<String,String> PRESETS=Map.ofEntries(
        Map.entry("温暖民谣", "Warm acoustic folk ballad, fingerpicked acoustic guitar, light piano, soft brushed percussion, relaxed 76-88 BPM, intimate natural singing, sparse organic arrangement"),
        Map.entry("抒情流行", "Emotional pop ballad, expressive melodic singing, piano-led verses, warm bass, restrained drums, soaring memorable chorus, gentle dynamic build, 72-88 BPM"),
        Map.entry("轻柔爵士", "Soft vocal jazz, warm piano voicings, upright bass, brushed jazz drums, gentle swing, intimate laid-back phrasing, small jazz ensemble, 76-94 BPM"),
        Map.entry("浪漫钢琴", "Romantic piano-led vocal ballad, expressive acoustic grand piano, delicate string support, sparse percussion, intimate melodic singing, flowing rubato feel, 64-80 BPM"),
        Map.entry("治愈轻音乐", "Soothing easy-listening vocal song, soft piano, gentle acoustic guitar, airy pads, delicate percussion, calm warm singing, restrained dynamics, peaceful 68-82 BPM"),
        Map.entry("清新校园", "Fresh youthful acoustic campus folk-pop, bright strummed acoustic guitar, light bass and drums, sincere clear singing, nostalgic and hopeful melody, 90-108 BPM"),
        Map.entry("国风古韵", "Chinese traditional-inspired vocal ballad, guzheng and pipa plucks, expressive erhu and dizi accents, pentatonic melody, delicate Chinese percussion, graceful lyrical singing, 70-90 BPM"),
        Map.entry("梦幻电子", "Dreamy electronic pop song, shimmering synthesizers, spacious atmospheric pads, soft electronic beats, deep rounded bass, ethereal melodic singing, 90-110 BPM"),
        Map.entry("轻快流行", "Upbeat bright pop song, crisp rhythmic drums, bouncy bass, clean guitar and sparkling keys, catchy sung chorus, optimistic energetic groove, 112-126 BPM"),
        Map.entry("复古港风", "Retro 1980s-1990s Hong Kong pop arrangement, warm electric piano, vintage synthesizers, melodic bass, gated snare, expressive nostalgic singing, polished romantic groove, 88-106 BPM"),
        Map.entry("R&B节奏蓝调", "Contemporary R&B song, soulful vocal phrasing, syncopated laid-back drums, deep smooth bass, lush electric piano chords, tasteful vocal runs, 72-92 BPM"),
        Map.entry("独立摇滚", "Indie rock song, driving live drums, prominent electric guitar riffs, crunchy guitar texture, melodic bass, raw expressive singing, energetic band arrangement, 116-140 BPM"),
        Map.entry("柔和声乐", "Intimate soft vocal ballad, close natural lead singing, delicate acoustic accompaniment, light piano and guitar, minimal percussion, gentle phrasing, 64-82 BPM"),
        Map.entry("电影配乐", "Cinematic orchestral vocal song, expressive sung melody, layered orchestral strings, warm piano, restrained orchestral percussion, evolving emotional swells, dramatic yet lyrical arrangement, 70-90 BPM"),
        Map.entry("浪漫弦乐", "Romantic string-led vocal ballad, legato violins and cello, delicate piano, warm orchestral harmonies, expressive melodic singing, gentle emotional build, 66-82 BPM"),
        Map.entry("轻快乡村", "Upbeat country song, bright acoustic guitar, tasteful banjo and fiddle, steady country drums, warm storytelling singing, lively organic band groove, 106-124 BPM"),
        Map.entry("甜蜜情歌", "Sweet romantic pop love song, warm melodic lead singing, gentle piano and acoustic guitar, soft bass and drums, tender memorable chorus, light affectionate mood, 80-100 BPM"),
        Map.entry("童趣儿歌", "Playful children's song, simple clear sung melody, short repeating musical phrases, bright ukulele, glockenspiel and handclaps, cheerful bouncy rhythm, 100-120 BPM"),
        Map.entry("舒缓Lo-fi", "Relaxed lo-fi vocal song, mellow Rhodes piano, dusty soft drum groove, warm rounded bass, subtle tape texture, understated intimate singing, 65-82 BPM"),
        Map.entry("清新波萨诺瓦", "Fresh bossa nova vocal song, nylon-string guitar syncopation, soft rim-click percussion, warm bass, jazzy extended chords, relaxed melodic singing, light Brazilian groove, 100-120 BPM"),
        Map.entry("热情拉丁", "Energetic Latin pop song, lively congas and timbales, rhythmic Latin guitar, bright brass accents, danceable syncopated bass, passionate melodic singing, 112-132 BPM"),
        Map.entry("轻快说唱", "Upbeat melodic hip-hop song, rhythmically spoken rap verses, clear rhythmic flow, catchy melodic hook, crisp hip-hop drums, bouncy bass, playful confident delivery, 90-108 BPM"),
        Map.entry("悠扬古典", "Lyrical classical-inspired vocal song, expressive piano, acoustic chamber strings and woodwinds, flowing elegant sung melody, restrained chamber arrangement, 60-80 BPM"),
        Map.entry("空灵氛围", "Ethereal ambient vocal song, spacious evolving pads, delicate bell textures, sparse gentle pulse, floating sustained vocal melody, airy reverb, tranquil spacious arrangement, 58-76 BPM")
    );

    static String expand(String style,String vocalGender) {
        String original=style.trim();
        String result=PRESETS.getOrDefault(original,original);
        // 与独立 vocal_gender 参数保持一致；自动选择时不预设音色或性别。
        return switch(vocalGender) {
            case "MALE" -> result+". Male lead vocals, singing the supplied lyrics.";
            case "FEMALE" -> result+". Female lead vocals, singing the supplied lyrics.";
            default -> result;
        };
    }
}
