package com.lhy.mest.client;

import java.text.Normalizer;
import java.util.Locale;

import com.ibm.icu.text.Transliterator;

/** Matches Latin needles against Chinese names via ICU Han-Latin (no extra mod required). */
public final class PinyinSearch {
    private static final Transliterator HAN_LATIN = Transliterator.getInstance("Han-Latin");

    private PinyinSearch() {
    }

    public static boolean contains(String haystack, String needle) {
        if (needle == null || needle.isEmpty()) {
            return true;
        }
        if (haystack == null || haystack.isEmpty()) {
            return false;
        }
        String hay = haystack.toLowerCase(Locale.ROOT);
        String need = needle.toLowerCase(Locale.ROOT);
        if (hay.contains(need)) {
            return true;
        }
        String latin = stripDiacritics(HAN_LATIN.transliterate(haystack)).toLowerCase(Locale.ROOT);
        String compactNeedle = need.replaceAll("[^a-z0-9]", "");
        if (compactNeedle.isEmpty()) {
            return false;
        }
        if (latin.replaceAll("[^a-z0-9]", "").contains(compactNeedle)) {
            return true;
        }
        return initials(latin).contains(compactNeedle);
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
