package com.lhy.mest.terminal;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DriveSearchSchedulerTest {
    @Test
    void burstKeepsOnlyLatestWindowAndStartsOnce() {
        var scheduler = new DriveSearchScheduler(5);
        for (int i = 0; i < 8; i++) scheduler.request(i * 256);
        assertEquals(7 * 256, scheduler.requestedStart());
        assertTrue(scheduler.start(100));
        assertFalse(scheduler.start(100));
    }

    @Test
    void duplicatePollDoesNotCancelInFlightGeneration() {
        var scheduler = new DriveSearchScheduler(5);
        assertTrue(scheduler.request(0));
        long generation = scheduler.generation();
        assertFalse(scheduler.request(0));
        assertEquals(generation, scheduler.generation());
        assertTrue(scheduler.request(256));
        assertNotEquals(generation, scheduler.generation());
    }

    @Test
    void newSearchCancelsOldGenerationWithoutBypassingCooldown() {
        var scheduler = new DriveSearchScheduler(5);
        scheduler.request(0);
        assertTrue(scheduler.start(100));
        long oldTask = scheduler.generation();
        scheduler.invalidate();
        assertNotEquals(oldTask, scheduler.generation());
        assertFalse(scheduler.start(100));
        assertFalse(scheduler.start(104));
        assertTrue(scheduler.start(105));
    }

    @Test
    void latestRequestSurvivesCooldownRatherThanBeingDropped() {
        var scheduler = new DriveSearchScheduler(5);
        scheduler.request(0);
        assertTrue(scheduler.start(10));
        scheduler.request(100);
        assertFalse(scheduler.start(11));
        scheduler.request(200);
        assertTrue(scheduler.start(15));
        assertEquals(200, scheduler.requestedStart());
    }

    @Test
    void repeatedBroadcastAndCloseReopenCannotMultiplyTickWork() {
        var scheduler = new DriveSearchScheduler(5);
        scheduler.request(0);
        assertTrue(scheduler.tick(100));
        assertTrue(scheduler.start(100));
        scheduler.clear();
        scheduler.request(0);
        assertFalse(scheduler.tick(100));
        assertFalse(scheduler.start(100));
        assertTrue(scheduler.tick(101));
        assertFalse(scheduler.tick(101));
        assertTrue(scheduler.start(105));
    }

    @Test
    void closeInvalidatesAndDiscardsPendingWork() {
        var scheduler = new DriveSearchScheduler(5);
        scheduler.request(0);
        long generation = scheduler.generation();
        scheduler.clear();
        assertNotEquals(generation, scheduler.generation());
        assertEquals(-1, scheduler.requestedStart());
        assertFalse(scheduler.start(100));
    }

    @Test
    void timeRollbackDoesNotPermanentlyStall() {
        var scheduler = new DriveSearchScheduler(5);
        scheduler.request(0);
        assertTrue(scheduler.start(100));
        scheduler.request(0);
        assertTrue(scheduler.start(1));
    }

    @Test
    void rejectsInvalidCooldown() {
        assertThrows(IllegalArgumentException.class, () -> new DriveSearchScheduler(0));
    }
}
