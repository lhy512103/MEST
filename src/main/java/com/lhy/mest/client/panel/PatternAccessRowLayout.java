package com.lhy.mest.client.panel;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure row-layout traversal shared by {@link PatternAccessPanel}'s rendering and hit-testing.
 *
 * <p>The pattern access view is a vertically scrolled list where each provider contributes one
 * header row followed by N slot rows. Rendering and slot hit-testing must walk this list with
 * identical skip/clip rules, otherwise the drawn slots and the clickable slots drift apart when
 * scrolled. This class is the single source of truth for that walk.
 */
final class PatternAccessRowLayout {
    private PatternAccessRowLayout() {
    }

    /**
     * One visible row. {@code slotRow < 0} marks a provider header; otherwise it is the provider's
     * slot-row index. {@code visibleRow} is the on-screen row (row 0 is reserved for the hint line).
     */
    record Row(int providerIndex, int slotRow, int visibleRow) {
        boolean isHeader() {
            return slotRow < 0;
        }
    }

    /**
     * Computes the visible rows for the given per-provider slot-row counts.
     *
     * @param providerSlotRowCounts number of slot rows for each provider, in list order
     * @param scrollRows            rows scrolled past (headers and slot rows count equally)
     * @param maxRows               total on-screen rows including the reserved hint row 0
     */
    static List<Row> visibleRows(List<Integer> providerSlotRowCounts, int scrollRows, int maxRows) {
        var result = new ArrayList<Row>();
        int row = 1;
        int skipped = Math.max(0, scrollRows);
        for (int providerIndex = 0; providerIndex < providerSlotRowCounts.size(); providerIndex++) {
            if (skipped > 0) {
                skipped--;
            } else if (row < maxRows) {
                result.add(new Row(providerIndex, -1, row));
                row++;
            }
            int slotRows = providerSlotRowCounts.get(providerIndex);
            for (int sr = 0; sr < slotRows; sr++) {
                if (skipped > 0) {
                    skipped--;
                    continue;
                }
                if (row >= maxRows) {
                    break;
                }
                result.add(new Row(providerIndex, sr, row));
                row++;
            }
            if (row >= maxRows) {
                break;
            }
        }
        return result;
    }
}
