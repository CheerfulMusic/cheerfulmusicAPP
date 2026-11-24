package com.qingfeng.music.server.hotsong;

import com.qingfeng.music.lyrics.application.LyricsStructure;
import com.qingfeng.music.settings.MusicSettings;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Map.entry;

/** Editorial directions for the recommendation tracks. The response contains no source lyric or audio. */
public final class HotSongCreationReferences {
    private HotSongCreationReferences() {}

    public record Reference(String title, String language, LyricsStructure structure, MusicSettings.Preferences preferences) {}
    public static final String HOUSE_ARTIST = "308036ef-8bef-486f-88cc-8266e5797768";
    private static final Map<String, Direction> HOUSE_DIRECTIONS = Map.of(
        "0485811c-0297-4513-9fb1-75f981af8707", en("electronic", "Deep melodic house, soft textures, low-end groove and a gradual lift."),
        "45e28036-c2bc-4f44-886c-3e40ddb32bf0", en("electronic", "Night-time melodic house, spacious synth textures and a steady club pulse."),
        "d29a567d-988a-44fe-b5d8-c15ab625a23a", en("electronic", "Rhythm-led house, syncopated bass and warm dancefloor percussion."));
    private record Direction(String language, String genre, String description, List<String> sections) {
        Reference forTitle(String title) {
            var structure = sections.isEmpty()
                ? new LyricsStructure("AUTO", false) : new LyricsStructure("CUSTOM", false, sections);
            return new Reference(title, language, structure,
                new MusicSettings.Preferences(1, null, Map.of("genre", List.of(genre)), "AUTO", description, ""));
        }
    }

    private static final Map<String, String> TITLES = Map.ofEntries(
        entry("love", "把平凡唱给你"), entry("family", "走了那么远"), entry("travel", "去有海风的地方"),
        entry("red-guofeng", "红"), entry("rain-disco", "忽然下起雨"), entry("tomorrow-rock", "明天再说"),
        entry("scenery-folk", "最美的风景"), entry("hometown-electronic", "hometown lover"),
        entry("river-instrumental", "When the River Breathes"));
    private static final Map<String, String> SAMPLE_TITLES = RecommendationSamples.SONGS.stream()
        .collect(Collectors.toUnmodifiableMap(RecommendationSamples.Song::id, RecommendationSamples.Song::title));

    private static final String V = "Verse", V1 = "Verse 1", V2 = "Verse 2", P = "Pre-Chorus",
        C = "Chorus", B = "Bridge", I = "Interlude";
    private static final Map<String, Direction> DIRECTIONS = Map.ofEntries(
        entry("love", zh("pop", "Intimate Mandarin love ballad, soft piano, warm acoustic textures and a close vocal.")),
        entry("family", zh("pop", "Reflective Mandarin pop ballad, warm acoustic guitar, gentle pulse and gradually opening choruses.")),
        entry("travel", zh("pop", "Carefree melodic pop with open road atmosphere, airy textures and a lilting beat.")),
        entry("red-guofeng", zh("chinese", "Poetic guofeng ballad, pentatonic melody, plucked traditional strings, restrained vocal and a flowing chorus.", V, P, C, I, P, C, C)),
        entry("rain-disco", zh("electronic", "Retro disco with four-on-the-floor kick, syncopated bass, bright accents and a danceable chorus.")),
        entry("tomorrow-rock", zh("rock", "Indie rock with electric guitar, live drum feel and an unpolished, direct vocal.")),
        entry("scenery-folk", zh("folk", "Warm acoustic folk, understated guitar and a conversational vocal.")),
        entry("hometown-electronic", en("electronic", "Dreamy electronic pop with a nostalgic city-night palette, soft synth layers and a steady pulse.")),
        entry("river-instrumental", en("pop", "Airy ambient arrangement, gently flowing melody and spacious instrumental textures.")),
        entry("feng-ye-xing", zh("pop", "Poetic Mandarin pop, nocturnal atmosphere, gentle opening and a widening melodic refrain.")),
        entry("ming-chui-si-hai", zh("chinese", "Chinese-inspired dramatic pop, forceful rhythmic accents and an expansive vocal refrain.")),
        entry("liu-lang-shi-ren", zh("pop", "Storytelling Mandarin pop, intimate phrasing and a recurring melodic chorus.", V, P, C, C, P, C, C)),
        entry("luo-yu-de-cao-chang", zh("pop", "Conversational Mandarin pop, gentle phrasing and a nostalgic refrain.", V1, V2, C, V, C)),
        entry("mei-jian-fu-yun", zh("pop", "Lyrical Mandarin pop, delicate phrasing and a light, expressive refrain.", V1, P, C, P, C)),
        entry("wu-ren-dai-ti", zh("pop", "Emotive Mandarin pop, a measured pace and a gradually unfolding chorus.", V, P, C, P, C)),
        entry("wu-ren-qu", zh("pop", "Brooding Mandarin pop, sparse verse textures and a strong repeated chorus.", V, P, C, C, P, C, C)),
        entry("wu-ye-zhi-shi", zh("pop", "Atmospheric Mandarin pop, theatrical dynamics and a slow-building recurrent chorus.", V1, V2, P, C, C)),
        entry("meng-de-chi-bang", zh("electronic", "Melodic electronic pop, atmospheric synths, emotional lead vocals and a rising pulse.")),
        entry("after-midnight", en("pop", "Night-time global pop, polished production and a strong melodic hook.")),
        entry("if-you-wanna-feel-me", en("pop", "Rhythmic global pop, short vocal lines and a repeated refrain.", V, C, V, C, C)),
        entry("where-the-wind-goes", en("pop", "Expansive global pop with a rising pre-chorus and a sustained melodic refrain.", V, P, C, P, C)),
        entry("body-language", en("electronic", "Minimal club house, four-on-the-floor drums, syncopated bass, rhythmic spoken phrases, a filtered build and a chopped vocal drop.", V, P, C)),
        entry("into-the-sun", en("electronic", "Atmospheric house, soulful lead, a gradual build and a hypnotic chant hook.", V, P, C, B)),
        entry("one-more-night", en("electronic", "Melodic house with a soft vocal, an emotional hook and a spacious outro.", V1, P, C, V2)),
        entry("all-night-one-more-time", en("electronic", "Club electronic with repeated vocal hooks, a strong kick and contrast between builds and drops.", C, I, C)),
        entry("euphoric-drop", en("electronic", "Uplifting instrumental electronic dance music, bright layered synths, festival build and a pronounced drop.")),
        entry("i-run-this-show", en("electronic", "Bass-forward club production with whispered accents and a chorus-first vocal hook.", C, I, V, C, I)),
        entry("light-it-up", en("electronic", "Festival EDM with a rising build, big layered synths and energetic drops.", V1, P, I, V2, P, I)),
        entry("cant-stop-this-feeling", en("pop", "Uplifting dance-pop, buoyant pulse and a repeated sing-along chorus.", V1, P, C, V2, P, C, B, C)),
        entry("midnight-call", en("pop", "Late-night synth-pop, conversational verses and a recurring melodic hook.", V1, P, C, V2, C)),
        entry("summer-on-repeat", en("pop", "Bright guitar-and-synth summer pop, energetic drums and an open sing-along hook.", V1, P, C, V2, B, C)),
        entry("golden-hour-love", en("rnb", "Warm soulful R&B with soft Rhodes, close lead vocals and layered gospel harmonies.", V1, P, C, V2, P, C, B, C)),
        entry("no-reply", en("rnb", "Late-night electronic R&B with whispered phrases, a clipped vocal hook and sparse textures.", V1, P, C, V2, P, C, B, C)),
        entry("too-close", en("rnb", "Intimate contemporary R&B with soft falsetto, layered harmonies and a recurring hook.", V1, P, C, V2, P, C, B, C)),
        entry("big-motion", en("rap", "Confident hip-hop with a recurring chant hook, tightly phrased verses and vocal ad-libs.", C, V1, P, C, V2, B, C)),
        entry("running-through-my-mind", en("rap", "Reflective melodic hip-hop with rhythmic verses and a sung chorus.", C, V1, P, C, V2, B, C)),
        entry("westside-summer", en("rap", "Upbeat West Coast hip-hop with cruising bass, relaxed rhythmic verses and a recurring summer chant hook.", C, V1, C, V2, B, C))
    );

    private static final Set<String> TRACK_IDS = Stream.concat(DIRECTIONS.keySet().stream(), HOUSE_DIRECTIONS.keySet().stream())
        .collect(Collectors.toUnmodifiableSet());

    public static Set<String> trackIds() {
        return TRACK_IDS;
    }

    public static Optional<Reference> find(String trackId) {
        var direction = DIRECTIONS.get(trackId);
        if (direction == null) return Optional.empty();
        return Optional.of(direction.forTitle(TITLES.getOrDefault(trackId, SAMPLE_TITLES.get(trackId))));
    }

    public static boolean isPublishedSelection(String trackId) {
        return HOUSE_DIRECTIONS.containsKey(trackId);
    }

    public static Reference forPublishedSelection(String trackId, String title) {
        return HOUSE_DIRECTIONS.get(trackId).forTitle(title);
    }

    private static Direction zh(String genre, String description, String... sections) {
        return new Direction("zh-CN", genre, description, List.of(sections));
    }

    private static Direction en(String genre, String description, String... sections) {
        return new Direction("en", genre, description, List.of(sections));
    }
}
