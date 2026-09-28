package com.lhy.mest.client;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.ibm.icu.text.Transliterator;

/**
 * Matches Latin needles against Chinese names via ICU Han-Latin (no extra mod required).
 *
 * <p>ICU transliteration is expensive — it dominated the render thread once the pattern panel
 * re-searched its providers every frame — while the set of names it is applied to is tiny and
 * stable. The normalized forms of a name are therefore memoized; only the needle is normalized
 * per call.
 */
public final class PinyinSearch {
    private static final Transliterator HAN_LATIN = Transliterator.getInstance("Han-Latin");
    /** Names are item/group display names, so a few thousand entries covers any pack. */
    private static final int CACHE_LIMIT = 8192;
    private static final Map<String, Forms> CACHE = new ConcurrentHashMap<>();

    private PinyinSearch() {
    }

    /** Pre-normalized haystack: compact latin (letters and digits only) plus its initials. */
    private record Forms(String compact, String initials) {
    }

    public static boolean contains(String haystack, String needle) {
        if (needle == null || needle.isEmpty()) {
            return true;
        }
        if (haystack == null || haystack.isEmpty()) {
            return false;
        }
        String need = needle.toLowerCase(Locale.ROOT);
        if (haystack.toLowerCase(Locale.ROOT).contains(need)) {
            return true;
        }
        String compactNeedle = compact(need);
        if (compactNeedle.isEmpty()) {
            return false;
        }
        Forms forms = forms(haystack);
        return forms.compact().contains(compactNeedle) || forms.initials().contains(compactNeedle);
    }

    /** Drops memoized names, for example after a resource-pack reload changes display names. */
    public static void clearCache() {
        CACHE.clear();
    }

    private static Forms forms(String haystack) {
        Forms cached = CACHE.get(haystack);
        if (cached != null) {
            return cached;
        }
        String latin = stripDiacritics(HAN_LATIN.transliterate(haystack)).toLowerCase(Locale.ROOT);
        Forms forms = new Forms(compact(latin), initials(latin));
        if (CACHE.size() < CACHE_LIMIT) {
            CACHE.put(haystack, forms);
        }
        return forms;
    }

    private static String compact(String value) {
        return value.replaceAll("[^a-z0-9]", "");
    }

    private static String stripDiacritics(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
    }

    private static String initials(String latin) {
        StringBuilder initials = new StringBuilder();
        boolean newSyllable = true;
        for (int i = 0; i < latin.length(); i++) {
            char character = latin.charAt(i);
            if (character >= 'a' && character <= 'z') {
                if (newSyllable) {
                    initials.append(character);
                }
                newSyllable = false;
            } else {
                newSyllable = true;
            }
        }
        return initials.toString();
    }
}
