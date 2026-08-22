package com.lhy.mest.client.panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.lhy.mest.client.panel.PatternAccessRowLayout.Row;

class PatternAccessRowLayoutTest {
    @Test
    void unscrolledLayoutStartsAtRowZeroWithHeaderThenSlotRows() {
        List<Row> rows = PatternAccessRowLayout.visibleRows(List.of(2), 0, 10);

        assertEquals(List.of(
                new Row(0, -1, 0),
                new Row(0, 0, 1),
                new Row(0, 1, 2)), rows);
    }

    @Test
    void scrollingSkipsHeaderAndSlotRowsUniformly() {
        List<Row> rows = PatternAccessRowLayout.visibleRows(List.of(2, 1), 2, 10);

        assertEquals(List.of(
                new Row(0, 1, 0),
                new Row(1, -1, 1),
                new Row(1, 0, 2)), rows);
    }

    @Test
    void headerRowsAreClippedAtMaxRowsJustLikeSlotRows() {
        List<Row> rows = PatternAccessRowLayout.visibleRows(List.of(1, 3), 0, 3);

        assertEquals(List.of(
                new Row(0, -1, 0),
                new Row(0, 0, 1),
                new Row(1, -1, 2)), rows);
        assertTrue(rows.stream().allMatch(row -> row.visibleRow() < 3));
    }

    @Test
    void traversalStopsAfterViewportIsFull() {
        List<Row> rows = PatternAccessRowLayout.visibleRows(List.of(5, 5, 5), 0, 4);

        assertEquals(4, rows.size());
        assertTrue(rows.stream().allMatch(row -> row.providerIndex() == 0));
    }

    @Test
    void largeScrollYieldsNoRows() {
        assertTrue(PatternAccessRowLayout.visibleRows(List.of(1, 1), 50, 10).isEmpty());
    }

    @Test
    void emptyProviderListYieldsNoRows() {
        assertTrue(PatternAccessRowLayout.visibleRows(List.of(), 0, 10).isEmpty());
    }
}
