package com.lhy.mest.compat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** Checks the API adapter separately from StowTransfer's item-accounting tests. */
class MestStowHotkeyWiringTest {
    private static final Path JAVA = Path.of("src/main/java/com/lhy/mest");

    @Test
    void liveCraftingTerminalYieldsToOriginalChainBeforeMestLookup() throws IOException {
        String stow = Files.readString(JAVA.resolve("compat/MestStowHotkey.java"));
        int guard = stow.indexOf("if (handler.inRange() && handler.getTargetGrid() != null)");
        int fallback = stow.indexOf("MestWtlibSupport.mestStack(player)");
        assertTrue(guard >= 0 && guard < fallback);
        assertTrue(stow.substring(guard, fallback).contains("return false;"));
        assertFalse(stow.contains("new StowHotkeyAction"));
    }

    @Test
    void fallbackKeepsWtlibMainHandProtectionAndLiveGridChecks() throws IOException {
        String stow = Files.readString(JAVA.resolve("compat/MestStowHotkey.java"));
        assertTrue(stow.contains("player.getItemInHand(InteractionHand.MAIN_HAND)"));
        assertFalse(stow.contains("InteractionHand.OFF_HAND"));
        assertTrue(stow.contains("held.isEmpty() || held.isNotReplaceableByPickAction(player, player.getInventory().selected)"));
        assertTrue(stow.contains("MestWtlibSupport.mestGrid(player)"));
        assertTrue(stow.contains("grid == null || grid.getStorageService() == null"));
        assertTrue(stow.contains("Actionable.MODULATE, new PlayerSource(player)"));
        assertTrue(stow.contains("held::setCount"));
        String support = Files.readString(JAVA.resolve("compat/MestWtlibSupport.java"));
        int start = support.indexOf("public static IGrid mestGrid(Player player)");
        int end = support.indexOf("private static MEStorage mestStorage", start);
        assertTrue(support.substring(start, end).contains("WTMenuHost host = linkedHost(player)"));
    }

    @Test
    void reusesExistingHotkeyRegistrationWithoutAddingAnotherBinding() throws IOException {
        String terminal = Files.readString(JAVA.resolve("terminal/MestTerminal.java"));
        assertTrue(terminal.contains("HotkeyAction.register(new MestStowHotkey(), MestStowHotkey.HOTKEY_ID)"));
        String stow = Files.readString(JAVA.resolve("compat/MestStowHotkey.java"));
        assertTrue(stow.contains("HOTKEY_ID = \"ae2wtlib_stow\""));
    }
}
