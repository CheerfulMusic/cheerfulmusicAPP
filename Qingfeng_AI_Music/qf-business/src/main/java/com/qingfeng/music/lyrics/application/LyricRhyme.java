package com.qingfeng.music.lyrics.application;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/** Finds rhyme endings in written Mandarin and American English lyrics. */
public final class LyricRhyme {
    public enum Match { EXACT, NEAR, CROSS_LANGUAGE, NONE }
    public record Tail(String word, String pronunciation, String language, String key) {}

    private static final String MANDARIN = "zh-CN";
    private static final String ENGLISH = "en";
    private static final String LATIN_WORD = "\\p{IsLatin}[\\p{IsLatin}\\p{M}]*(?:['.-]\\p{IsLatin}[\\p{IsLatin}\\p{M}]*)*";
    private static final Pattern ENDING = Pattern.compile("(\\p{IsHan}+|" + LATIN_WORD + ")$");
    private static final Pattern SYLLABLE_TOKENS = Pattern.compile("\\p{IsHan}|" + LATIN_WORD + "|[\\p{L}\\p{N}]");
    private static final Pattern TRAILING_PUNCTUATION = Pattern.compile("[\\p{Z}\\p{P}\\p{S}\\uFE0E\\uFE0F]+$");
    private static final Pattern PINYIN_INITIAL = Pattern.compile("^(zh|ch|sh|[bpmfdtnlgkhjqxrzcs])");
    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
    private static final Pattern PRONUNCIATION_VARIANT = Pattern.compile("\\(\\d+\\)$");
    private static final Set<String> AMBIGUOUS_HAN = Set.of("行", "重", "长", "長", "乐", "樂", "还", "還", "藏", "得", "转", "轉", "数", "數", "都", "调", "調", "差", "便", "觉", "覺", "着", "著", "了", "地", "和");
    private static final Set<String> TERMINAL_PARTICLES = Set.of("了", "着", "著", "呢", "吧", "啊", "呀", "啦", "吗", "嗎", "嘛", "哟", "喲");
    private static final Map<String, String> TONGYUN = Map.ofEntries(
        Map.entry("ia", "a"), Map.entry("ua", "a"), Map.entry("uo", "o"),
        Map.entry("ie", "e"), Map.entry("üe", "e"), Map.entry("-i", "i"),
        Map.entry("uai", "ai"), Map.entry("uei", "ei"), Map.entry("iao", "ao"), Map.entry("iou", "ou"),
        Map.entry("ian", "an"), Map.entry("uan", "an"), Map.entry("üan", "an"),
        Map.entry("in", "en"), Map.entry("uen", "en"), Map.entry("ün", "en"),
        Map.entry("iang", "ang"), Map.entry("uang", "ang"),
        Map.entry("ing", "eng"), Map.entry("ueng", "eng"), Map.entry("iong", "ong")
    );
    private static final Set<String> RHYME_GROUPS = Set.of("a", "o", "e", "i", "u", "ü", "ai", "ei", "ao", "ou", "an", "en", "ang", "eng", "ong", "er");
    // Compare the audible vowel and coda conservatively for optional cross-language near rhyme.
    private static final Map<String, String> CROSS_LANGUAGE_ENDINGS = Map.ofEntries(
        Map.entry("a", "AA"), Map.entry("ia", "AA"), Map.entry("ua", "AA"),
        Map.entry("ai", "AY"), Map.entry("uai", "AY"), Map.entry("ei", "EY"), Map.entry("uei", "EY"),
        Map.entry("ao", "AW"), Map.entry("iao", "AW"), Map.entry("ou", "OW"), Map.entry("iou", "OW"),
        Map.entry("i", "IY"), Map.entry("u", "UW"), Map.entry("an", "AE N"),
        Map.entry("in", "IY N"), Map.entry("ing", "IY NG"), Map.entry("ang", "AA NG"),
        Map.entry("iang", "AA NG"), Map.entry("uang", "AA NG")
    );

    private LyricRhyme() {}

    public static Optional<Tail> tail(String line) {
        String text = TRAILING_PUNCTUATION.matcher(normalize(line)).replaceFirst("");
        var ending = ENDING.matcher(text);
        if (!ending.find()) return Optional.empty();
        String word = ending.group();
        return isHan(word.codePointAt(0)) ? mandarinTail(word) : englishTail(word);
    }

    public static Match match(Tail first, Tail second, boolean allowCrossLanguage) {
        if (!first.language().equals(second.language())) {
            if (!allowCrossLanguage) return Match.NONE;
            Tail mandarin = first.language().equals(MANDARIN) ? first : second;
            Tail english = first.language().equals(ENGLISH) ? first : second;
            String crossKey = CROSS_LANGUAGE_ENDINGS.get(pinyinFinal(mandarin.pronunciation()));
            return english.key().equals(crossKey) ? Match.CROSS_LANGUAGE : Match.NONE;
        }
        if (first.key().equals(second.key())) {
            if (first.language().equals(MANDARIN) && Set.of("e", "i").contains(first.key())
                && !pinyinFinal(first.pronunciation()).equals(pinyinFinal(second.pronunciation()))) return Match.NEAR;
            return Match.EXACT;
        }
        if (first.language().equals(ENGLISH) && sameEnglishVowel(first.key(), second.key())) return Match.NEAR;
        return Match.NONE;
    }

    /** Returns -1 when the written line does not determine an unambiguous count. */
    public static int syllableCount(String line) {
        var tokens = SYLLABLE_TOKENS.matcher(normalize(line));
        int count = 0;
        while (tokens.find()) {
            String token = tokens.group();
            if (isHan(token.codePointAt(0))) {
                count++;
                continue;
            }
            List<EnglishPronunciation> readings = englishReadings(token);
            if (readings.isEmpty()) return -1;
            int syllables = readings.get(0).syllables();
            if (readings.stream().anyMatch(reading -> reading.syllables() != syllables)) return -1;
            count += syllables;
        }
        return count == 0 ? -1 : count;
    }

    private static Optional<Tail> mandarinTail(String text) {
        int last = text.offsetByCodePoints(text.length(), -1);
        String word = text.substring(last);
        int suffixStart = text.offsetByCodePoints(text.length(), -Math.min(10, text.codePointCount(0, text.length())));
        for (int start = suffixStart; start < last; start += Character.charCount(text.codePointAt(start))) {
            String[] readings = MandarinDictionary.READINGS.get(text.substring(start));
            if (readings != null) return uniqueMandarinTail(word, readings);
        }
        String[] readings = MandarinDictionary.READINGS.get(word);
        if (readings == null) return Optional.empty();
        if (TERMINAL_PARTICLES.contains(word) && last > 0) return mandarinReading(word, readings[0]);
        if (AMBIGUOUS_HAN.contains(word)) return uniqueMandarinTail(word, readings);
        return mandarinReading(word, readings[0]);
    }

    private static Optional<Tail> uniqueMandarinTail(String word, String[] readings) {
        Optional<Tail> first = mandarinReading(word, readings[0]);
        for (int i = 1; i < readings.length; i++) {
            Optional<Tail> other = mandarinReading(word, readings[i]);
            if (first.isEmpty() || other.isEmpty() || !first.get().key().equals(other.get().key())) return Optional.empty();
        }
        return first;
    }

    private static Optional<Tail> mandarinReading(String word, String reading) {
        String pinyin = pinyinFinal(reading);
        String group = TONGYUN.getOrDefault(pinyin, pinyin);
        return RHYME_GROUPS.contains(group) ? Optional.of(new Tail(word, reading, MANDARIN, group)) : Optional.empty();
    }

    private static String pinyinFinal(String reading) {
        String decomposed = Normalizer.normalize(reading.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replace("u\u0308", "ü");
        String plain = DIACRITICS.matcher(decomposed).replaceAll("");
        if (plain.startsWith("yu")) return "ü" + plain.substring(2);
        if (plain.startsWith("yi") || plain.startsWith("wu")) return plain.substring(1);
        if (plain.startsWith("y")) return "i" + plain.substring(1);
        if (plain.startsWith("w")) return "u" + plain.substring(1);
        if (plain.matches("[jqx]u.*")) return "ü" + plain.substring(2);
        if (plain.matches("(zh|ch|sh|[zcsr])i")) return "-i";
        String ending = PINYIN_INITIAL.matcher(plain).replaceFirst("");
        return switch (ending) {
            case "ui" -> "uei";
            case "iu" -> "iou";
            case "un" -> "uen";
            default -> ending;
        };
    }

    private static Optional<Tail> englishTail(String word) {
        var readings = englishReadings(word);
        if (readings.isEmpty()) return Optional.empty();
        EnglishPronunciation first = readings.get(0);
        if (readings.stream().anyMatch(reading -> !first.key().equals(reading.key()))) return Optional.empty();
        return Optional.of(new Tail(word, first.ipa(), ENGLISH, first.key()));
    }

    private static List<EnglishPronunciation> englishReadings(String word) {
        String lookup = word.toLowerCase(Locale.ROOT);
        List<EnglishPronunciation> readings = EnglishDictionary.READINGS.get(lookup);
        if (readings != null) return readings;
        int separator = lookup.lastIndexOf('-');
        if (separator < 0) return List.of();
        var prefix = englishReadings(lookup.substring(0, separator));
        var suffix = englishReadings(lookup.substring(separator + 1));
        if (prefix.isEmpty() || suffix.isEmpty()) return List.of();
        int prefixLength = prefix.get(0).syllables();
        if (prefix.stream().anyMatch(reading -> reading.syllables() != prefixLength)) return List.of();
        return suffix.stream().map(reading -> new EnglishPronunciation(reading.key(),
            prefix.get(0).ipa() + " " + reading.ipa(), prefixLength + reading.syllables())).toList();
    }

    private static boolean sameEnglishVowel(String first, String second) {
        String[] a = first.split(" ");
        String[] b = second.split(" ");
        if (!a[0].equals(b[0])) return false;
        // Allow a different consonant coda for assonance. Preserve unstressed vowels of longer rhymes.
        return java.util.Arrays.stream(a).filter(LyricRhyme::isEnglishVowel).toList()
            .equals(java.util.Arrays.stream(b).filter(LyricRhyme::isEnglishVowel).toList());
    }

    private static boolean isEnglishVowel(String phone) {
        return switch (phone) {
            case "AA", "AE", "AH", "AO", "AW", "AY", "EH", "ER", "EY", "IH", "IY", "OW", "OY", "UH", "UW" -> true;
            default -> false;
        };
    }

    private static boolean isHan(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }

    private static String normalize(String line) {
        if (line == null) return "";
        return Normalizer.normalize(line, Normalizer.Form.NFKC).strip().replace('\u2019', '\'').replace('\u02bc', '\'');
    }

    private static BufferedReader dictionary(String name) throws IOException {
        var stream = LyricRhyme.class.getResourceAsStream("/lyric-rhyme/" + name);
        if (stream == null) throw new IOException("Missing pronunciation dictionary: " + name);
        return new BufferedReader(new InputStreamReader(new GZIPInputStream(stream), StandardCharsets.UTF_8));
    }

    private static final class MandarinDictionary {
        private static final Map<String, String[]> READINGS = load();

        private static Map<String, String[]> load() {
            Map<String, String[]> result = new HashMap<>();
            try (var reader = dictionary("mandarin.tsv.gz")) {
                for (String line; (line = reader.readLine()) != null;) {
                    int tab = line.indexOf('\t');
                    result.put(line.substring(0, tab), line.substring(tab + 1).split(","));
                }
                return Map.copyOf(result);
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }
    }

    private record EnglishPronunciation(String key, String ipa, int syllables) {
        private static EnglishPronunciation parse(String phonemes) {
            String[] phones = phonemes.split(" ");
            var ipa = new StringBuilder();
            int stressed = -1, lastVowel = -1, syllables = 0;
            for (int i = 0; i < phones.length; i++) {
                String phone = phones[i];
                char stress = phone.charAt(phone.length() - 1);
                if (stress >= '0' && stress <= '2') {
                    phone = phone.substring(0, phone.length() - 1);
                    phones[i] = phone;
                    lastVowel = i;
                    syllables++;
                    if (stress != '0') stressed = i;
                }
                ipa.append(ipaPhone(phone, stress));
            }
            if (lastVowel < 0) return null;
            int rhymeStart = stressed >= 0 ? stressed : lastVowel;
            String key = String.join(" ", java.util.Arrays.copyOfRange(phones, rhymeStart, phones.length));
            return new EnglishPronunciation(key, "/" + ipa + "/", syllables);
        }

        private static String ipaPhone(String phone, char stress) {
            return switch (phone) {
                case "AA" -> "ɑ";
                case "AE" -> "æ";
                case "AH" -> stress == '0' ? "ə" : "ʌ";
                case "AO" -> "ɔ";
                case "AW" -> "aʊ";
                case "AY" -> "aɪ";
                case "EH" -> "ɛ";
                case "ER" -> stress == '0' ? "ɚ" : "ɝ";
                case "EY" -> "eɪ";
                case "IH" -> "ɪ";
                case "IY" -> "i";
                case "OW" -> "oʊ";
                case "OY" -> "ɔɪ";
                case "UH" -> "ʊ";
                case "UW" -> "u";
                case "CH" -> "tʃ";
                case "DH" -> "ð";
                case "HH" -> "h";
                case "JH" -> "dʒ";
                case "NG" -> "ŋ";
                case "R" -> "ɹ";
                case "SH" -> "ʃ";
                case "TH" -> "θ";
                case "Y" -> "j";
                case "ZH" -> "ʒ";
                default -> phone.toLowerCase(Locale.ROOT);
            };
        }
    }

    private static final class EnglishDictionary {
        private static final Map<String, List<EnglishPronunciation>> READINGS = load();

        private static Map<String, List<EnglishPronunciation>> load() {
            Map<String, List<EnglishPronunciation>> result = new HashMap<>();
            try (var reader = dictionary("cmudict.dict.gz")) {
                for (String row; (row = reader.readLine()) != null;) {
                    int comment = row.indexOf('#');
                    String line = (comment < 0 ? row : row.substring(0, comment)).strip();
                    if (line.isEmpty()) continue;
                    int separator = line.indexOf(' ');
                    String word = PRONUNCIATION_VARIANT.matcher(line.substring(0, separator)).replaceFirst("");
                    EnglishPronunciation reading = EnglishPronunciation.parse(line.substring(separator + 1).strip());
                    if (reading != null) result.computeIfAbsent(word, ignored -> new ArrayList<>()).add(reading);
                }
                return Map.copyOf(result);
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }
    }
}
