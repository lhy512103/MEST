package com.lhy.mest.client.dock.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ContentNudgeTest {
    @Test
    void clampKeepsOffsetInsideSlack() {
        ContentOffset offset = ContentNudge.clamp(40, -3, 20, 10);
        assertEquals(new ContentOffset(20, 0), offset);
    }

    @Test
    void clampZeroesWhenThereIsNoSlack() {
        assertEquals(ContentOffset.ZERO, ContentNudge.clamp(8, 8, 0, 0));
    }

    @Test
    void snapLocksOntoCentreWhenClose() {
        ContentNudge.Snap snap = ContentNudge.snap(8, 0, 20, 10);
        assertEquals(10, snap.offset().x());
        assertEquals(0, snap.offset().y());
        assertTrue(snap.snapX());
        assertFalse(snap.snapY());
    }

    @Test
    void snapDoesNotStickFarFromCentre() {
        ContentNudge.Snap snap = ContentNudge.snap(2, 2, 20, 20);
        assertEquals(new ContentOffset(2, 2), snap.offset());
        assertFalse(snap.snapX());
        assertFalse(snap.snapY());
    }
}
