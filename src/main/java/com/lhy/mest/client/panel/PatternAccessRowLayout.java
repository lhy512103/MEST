package com.lhy.mest.client.panel;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure row-layout traversal shared by {@link PatternAccessPanel}'s rendering and hit-testing.
 *
 * <p>The pattern access view is a vertically scrolled list where each provider contributes an
 * optional header row followed by N slot rows, matching ExtendedAE's grouped pattern terminal.
 */
final class PatternAccessRowLayout {
    private PatternAccessRowLayout() {
    }

    /**
     * One visible row. {@code slotRow < 0} marks a provider/group header; otherwise it is the
     * provider's slot-row index. {@code visibleRow} is the on-screen row starting at 0.
     */
    record Row(int providerIndex, int slotRow, int visibleRow) {
        boolean isHeader() {
            return slotRow < 0;
        }
    }

    record ProviderRows(int providerIndex, int slotRows, boolean header) {
    }

    static List<Row> visibleRows(List<Integer> providerSlotRowCounts, int scrollRows, int maxRows) {
        var specs = new ArrayList<ProviderRows>(providerSlotRowCounts.size());
        for (int i = 0; i < providerSlotRowCounts.size(); i++) {
            specs.add(new ProviderRows(i, providerSlotRowCounts.get(i), true));
        }
        return visibleRowsFromSpecs(specs, scrollRows, maxRows);
    }

    static List<Row> visibleRowsFromSpecs(List<ProviderRows> providers, int scrollRows, int maxRows) {
        var result = new ArrayList<Row>();
        int row = 0;
        int skipped = Math.max(0, scrollRows);
        for (ProviderRows provider : providers) {
            if (provider.header()) {
                if (skipped > 0) {
                    skipped--;
                } else if (row < maxRows) {
                    result.add(new Row(provider.providerIndex(), -1, row));
                    row++;
                }
            }
            for (int sr = 0; sr < provider.slotRows(); sr++) {
                if (skipped > 0) {
                    skipped--;
                    continue;
                }
                if (row >= maxRows) {
                    return result;
                }
                result.add(new Row(provider.providerIndex(), sr, row));
                row++;
            }
            if (row >= maxRows) {
                break;
            }
        }
        return result;
    }
}
