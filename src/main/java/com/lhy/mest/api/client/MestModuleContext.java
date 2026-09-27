package com.lhy.mest.api.client;

import java.util.List;

import net.minecraft.world.inventory.Slot;

import appeng.client.gui.style.ScreenStyle;

import com.lhy.mest.client.MESTScreen;
import com.lhy.mest.client.dock.DockManager;
import com.lhy.mest.terminal.MESTMenu;

/** What a module factory sees when the terminal screen builds its panels. */
public interface MestModuleContext {
    String moduleId();

    MESTMenu menu();

    MESTScreen screen();

    ScreenStyle style();

    DockManager dock();

    /** Slots this module registered through {@code RegisterMestModuleSlotsEvent}, in order. */
    List<Slot> slots();
}
