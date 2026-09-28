package com.lhy.mest.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Geometry regression tests for the toolkit quick bars.
 *
 * <p>The vanilla hotbar sprite is a fixed 182x22 artwork for nine 20px cells, so the sprite may only
 * be drawn at a width derived from the cell pitch. Drawing it wider stretches the cell borders away
 * from the item positions, which is exactly the misalignment these tests lock down.
 */
class ToolkitBarHudTest {
    private static final int HOTBAR_HALF = 91;
    private static final int BAR_HEIGHT = 22;

    @Test
    void barWidthKeepsTheVanillaSpriteAspect() {
        // Vanilla: nine 20px cells plus the 1px border on each end.
        assertEquals(182, ToolkitBarHud.barWidth(20));
    }

    @Test
    void cellPitchIsCappedAtVanillaAndFlooredAtTheMinimum() {
        // Plenty of room: never exceed the vanilla pitch.
        assertEquals(20, ToolkitBarHud.slotSize(1920, 0));
        // Very narrow: still usable, never zero or negative.
        int narrow = ToolkitBarHud.slotSize(200, 0);
        assertTrue(narrow >= 12, "pitch must stay usable, was " + narrow);
    }

    @Test
    void barsSitSymmetricallyBesideTheHotbarWithoutAnOffhandItem() {
        int guiWidth = 640;
        int slot = ToolkitBarHud.slotSize(guiWidth, 0);
        int barWidth = ToolkitBarHud.barWidth(slot);
        int hotbarLeft = guiWidth / 2 - HOTBAR_HALF;
        int hotbarRight = guiWidth / 2 + HOTBAR_HALF;

        int leftX = ToolkitBarHud.leftX(guiWidth, slot, 0);
        int rightX = ToolkitBarHud.rightX(guiWidth, slot, 0);

        assertEquals(hotbarLeft - (leftX + barWidth), rightX - hotbarRight,
                "gap between each bar and the hotbar must match");
        assertTrue(leftX >= 0, "left bar must stay on screen");
        assertTrue(rightX + barWidth <= guiWidth, "right bar must stay on screen");
    }

    @Test
    void offhandGapIsReservedOnTheCorrectSide() {
        // With an offhand item the left bar shifts out by the 29px offhand well, so the gap between
        // the bar and the hotbar stays the same as the right side's.
        int guiWidth = 640;
        int leftSlot = ToolkitBarHud.slotSize(guiWidth, 29);
        int rightSlot = ToolkitBarHud.slotSize(guiWidth, 0);
        int leftEnd = ToolkitBarHud.leftX(guiWidth, leftSlot, 29) + ToolkitBarHud.barWidth(leftSlot);
        int offhandLeft = guiWidth / 2 - HOTBAR_HALF - 29;

        assertEquals(offhandLeft - leftEnd, ToolkitBarHud.rightX(guiWidth, rightSlot, 0)
                - (guiWidth / 2 + HOTBAR_HALF), "offhand side must keep the same visual gap");
    }

    @Test
    void clickingACellMapsToThatCellIndex() {
        int guiWidth = 640;
        int guiHeight = 480;
        int slot = ToolkitBarHud.slotSize(guiWidth, 0);
        int leftX = ToolkitBarHud.leftX(guiWidth, slot, 0);
        int rightX = ToolkitBarHud.rightX(guiWidth, slot, 0);
        int y = ToolkitBarHud.barY(guiHeight) + 1;

        for (int cell = 0; cell < ToolkitBarHud.BAR_SLOTS; cell++) {
            // Aim at the middle of each cell, offset by the item inset like the renderer does.
            double leftMouse = leftX + ToolkitBarHud.ITEM_INSET + cell * slot + slot / 2.0;
            double rightMouse = rightX + ToolkitBarHud.ITEM_INSET + cell * slot + slot / 2.0;
            assertEquals(cell, ToolkitBarHud.slotAt(guiWidth, guiHeight, 0, 0, leftMouse, y),
                    "left bar cell " + cell);
            assertEquals(cell + ToolkitBarHud.BAR_SLOTS,
                    ToolkitBarHud.slotAt(guiWidth, guiHeight, 0, 0, rightMouse, y),
                    "right bar cell " + cell);
        }
    }

    @Test
    void clicksOutsideTheBarsAreIgnored() {
        int guiWidth = 640;
        int guiHeight = 480;
        int hotbarMiddle = guiWidth / 2;
        // Vanilla hotbar cells must not be captured by the toolkit bars.
        assertEquals(-1, ToolkitBarHud.slotAt(guiWidth, guiHeight, 0, 0, hotbarMiddle,
                ToolkitBarHud.barY(guiHeight) + 1));
        // Above the bars is the world, not the HUD.
        assertEquals(-1, ToolkitBarHud.slotAt(guiWidth, guiHeight, 0, 0, 5,
                ToolkitBarHud.barY(guiHeight) - BAR_HEIGHT));
    }
}
