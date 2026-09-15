package com.lhy.mest.terminal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ToolkitSyncPolicyTest {
    @Test
    void adoptsTheSnapshotWhenNothingWasWrittenLocally() {
        assertTrue(ToolkitSyncPolicy.shouldAdoptFromComponent(false, false));
        assertFalse(ToolkitSyncPolicy.shouldAdoptFromComponent(false, true));
    }

    @Test
    void neverAdoptsOverAPendingLocalEdit() {
        // The armor swap left the displaced item in the cell while the snapshot still holds the
        // equipped one: adopting here is what duplicated the item in the bar.
        assertFalse(ToolkitSyncPolicy.shouldAdoptFromComponent(true, false));
    }

    @Test
    void settlesThePendingEditOnceTheSnapshotAgrees() {
        assertTrue(ToolkitSyncPolicy.isLocalEditSettled(true, true));
        assertFalse(ToolkitSyncPolicy.isLocalEditSettled(true, false));
        assertFalse(ToolkitSyncPolicy.isLocalEditSettled(false, true));
    }
}
