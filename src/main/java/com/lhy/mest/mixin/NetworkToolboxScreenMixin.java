package com.lhy.mest.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.inventory.Slot;

import appeng.client.Point;
import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.WidgetContainer;
import appeng.client.gui.layout.SlotGridLayout;
import appeng.client.gui.style.SlotPosition;
import appeng.core.localization.GuiText;
import appeng.menu.AEBaseMenu;
import appeng.menu.SlotSemantic;
import appeng.menu.SlotSemantics;

import com.lhy.mest.client.MESTScreen;
import com.lhy.mest.client.dock.ToolboxChrome;

/**
 * AE2's toolbox chrome is a fixed 3×3 panel that is only added to some screens, and it stacks any
 * extra upgrade slots on top of itself. Every AE screen with TOOLBOX slots gets MEST's own compact
 * panel instead: same anchor (the style's TOOLBOX slot position), 3×3 visible rows plus a scrollbar
 * when the network toolkit holds more than nine slots.
 */
@Mixin(AEBaseScreen.class)
public abstract class NetworkToolboxScreenMixin {
    @Shadow
    @Final
    protected WidgetContainer widgets;

    @Unique
    private ToolboxChrome mest$toolbox;

    @Inject(method = "init", at = @At("RETURN"))
    private void mest$installToolbox(CallbackInfo ci) {
        mest$refreshToolbox((AEBaseScreen<?>) (Object) this);
    }

    @Inject(method = "repositionSlots", at = @At("RETURN"), remap = false)
    private void mest$repositionToolboxSlots(SlotSemantic semantic, CallbackInfo ci) {
        if (semantic != SlotSemantics.TOOLBOX) {
            return;
        }
        mest$refreshToolbox((AEBaseScreen<?>) (Object) this);
        if (mest$toolbox != null) {
            mest$toolbox.placeSlots();
        }
    }

    @Unique
    private void mest$refreshToolbox(AEBaseScreen<?> screen) {
        if (screen instanceof MESTScreen || !(screen.getMenu() instanceof AEBaseMenu menu)) {
            return;
        }
        List<Slot> slots = menu.getSlots(SlotSemantics.TOOLBOX);
        if (slots.isEmpty() || !ToolboxChrome.hasVisibleSlots(slots)) {
            mest$removeToolbox();
            return;
        }
        if (mest$toolbox != null && mest$toolbox.ownsSlot(slots.get(0))) {
            return;
        }
        mest$toolbox = new ToolboxChrome(slots, GuiText.NetworkTool.text());
        mest$toolbox.setPosition(mest$toolboxPosition(screen));
        ((WidgetContainerAccessor) widgets).mest$compositeWidgets().put("toolbox", mest$toolbox);
    }

    @Unique
    private void mest$removeToolbox() {
        if (mest$toolbox != null) {
            var composites = ((WidgetContainerAccessor) widgets).mest$compositeWidgets();
            if (composites.get("toolbox") == mest$toolbox) {
                composites.remove("toolbox");
            }
            mest$toolbox.hide();
            mest$toolbox = null;
        }
    }

    /**
     * Anchor the panel so its first slot lands exactly where AE2 would have put the first vanilla
     * toolbox slot: the style's TOOLBOX position, resolved against the GUI image.
     */
    @Unique
    private static Point mest$toolboxPosition(AEBaseScreen<?> screen) {
        SlotPosition slotPosition = screen.getStyle().getSlots().get(SlotSemantics.TOOLBOX.id());
        if (slotPosition != null) {
            Point anchor = slotPosition.resolve(new Rect2i(0, 0, screen.getXSize(), screen.getYSize()));
            SlotGridLayout grid = slotPosition.getGrid();
            if (grid != null) {
                anchor = grid.getPosition(0, anchor.getX(), anchor.getY());
            }
            return new Point(
                    anchor.getX() - ToolboxChrome.SLOT_ORIGIN,
                    anchor.getY() - ToolboxChrome.SLOT_ORIGIN);
        }
        return new Point(
                screen.getXSize() - 1 - ToolboxChrome.SLOT_ORIGIN,
                screen.getYSize() - 84 - ToolboxChrome.SLOT_ORIGIN);
    }
}
