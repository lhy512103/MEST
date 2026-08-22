package com.lhy.mest.client.dock;

import java.util.List;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;

/** Right-hand extra column hung off the anchored terminal group (upgrades, view cells). */
public interface ExtraChrome {
    boolean hasSlots();

    boolean isVisible();

    Rect2i bounds();

    int nextColumnX();

    boolean contains(double mx, double my);

    boolean ownsSlot(Slot slot);

    void layoutAgainst(int attachX, int attachY, boolean show);

    void hide();

    void renderBackground(GuiGraphics g);

    void renderSlots(GuiGraphics g, ModulePanel.PanelSlotRenderer renderer);

    List<Component> chromeTooltipAt(double mx, double my);

    default boolean mouseClicked(double mx, double my, int button) {
        return false;
    }

    default boolean mouseReleased(double mx, double my, int button) {
        return false;
    }

    default boolean mouseDragged(double mx, double my) {
        return false;
    }

    default boolean mouseScrolled(double mx, double my, double delta) {
        return false;
    }
}
