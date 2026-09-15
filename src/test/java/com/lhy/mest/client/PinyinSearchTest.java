package com.lhy.mest.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PinyinSearchTest {
    @Test
    void matchesPlainSubstringsCaseInsensitively() {
        assertTrue(PinyinSearch.contains("Iron Ingot", "iron"));
        assertTrue(PinyinSearch.contains("Iron Ingot", "INGOT"));
        assertFalse(PinyinSearch.contains("Iron Ingot", "gold"));
    }

    @Test
    void matchesChineseNamesByPinyinAndInitials() {
        assertTrue(PinyinSearch.contains("铁锭", "tie"));
        assertTrue(PinyinSearch.contains("铁锭", "td"));
    }

    @Test
    void emptyNeedleMatchesEverythingAndEmptyHaystackDoesNot() {
        assertTrue(PinyinSearch.contains("anything", ""));
        assertTrue(PinyinSearch.contains("anything", null));
        assertFalse(PinyinSearch.contains("", "iron"));
        assertFalse(PinyinSearch.contains(null, "iron"));
    }

    @Test
    void memoizedLookupsReturnTheSameAnswers() {
        for (int i = 0; i < 3; i++) {
            assertTrue(PinyinSearch.contains("铁锭", "td"));
            assertFalse(PinyinSearch.contains("铁锭", "zz"));
        }
    }

    @Test
    void clearCacheKeepsResultsStable() {
        assertTrue(PinyinSearch.contains("铁锭", "tie"));
        PinyinSearch.clearCache();
        assertEquals(true, PinyinSearch.contains("铁锭", "tie"));
    }
}
