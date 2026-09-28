package com.lhy.mest.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ToolkitBarTogglePacketTest {

    @Test
    void recordKeepsEnabledFlag() {
        assertTrue(new ToolkitBarTogglePacket(true).enabled());
        assertFalse(new ToolkitBarTogglePacket(false).enabled());
        assertEquals(
                new ToolkitBarTogglePacket(true),
                new ToolkitBarTogglePacket(true));
    }
}
