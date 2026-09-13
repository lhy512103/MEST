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
import appeng.client.gui.ICompositeWidget;
import appeng.client.gui.WidgetContainer;
import appeng.client.gui.layout.SlotGridLayout;
import appeng.client.gui.style.SlotPosition;
import appeng.core.localization.GuiText;
import appeng.menu.AEBaseMenu;
import appeng.menu.SlotSemantic;
import appeng.menu.SlotSemantics;

import com.lhy.mest.MESplicedterminal;
import com.lhy.mest.client.MESTScreen;
import com.lhy.mest.client.dock.ToolboxChrome;
import com.lhy.mest.terminal.MestNetworkToolkitAccess;

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

    /** The vanilla {@code ToolboxPanel} we displaced, restored when a vanilla tool takes over. */
    @Unique
    private ICompositeWidget mest$replaced;

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
        // A carried vanilla network tool keeps AE2's own panel; only the spliced terminal's larger
        // toolbox gets MEST's scrolling chrome.
        if (slots.isEmpty() || !MestNetworkToolkitAccess.isTerminalToolbox(slots.get(0))
                || !ToolboxChrome.hasVisibleSlots(slots)) {
            mest$removeToolbox();
            return;
        }
        if (mest$toolbox != null && mest$toolbox.ownsSlot(slots.get(0))) {
            // AE2 re-populates widgets on every init() and re-anchors any "toolbox" composite to the
            // vanilla widget position, so our placement has to be re-asserted here.
            mest$toolbox.setPosition(mest$toolboxPosition(screen));
            return;
        }
        mest$toolbox = new ToolboxChrome(slots, GuiText.NetworkTool.text());
        Point position = mest$toolboxPosition(screen);
        mest$toolbox.setPosition(position);
        mest$replaced = ((WidgetContainerAccessor) widgets).mest$compositeWidgets().put("toolbox", mest$toolbox);
        MESplicedterminal.LOGGER.info(
                "MEST: network toolbox panel at {},{} ({} slots, skin shift {})",
                position.getX(), position.getY(), slots.size(), ToolboxChrome.shiftX());
    }

    @Unique
    private void mest$removeToolbox() {
        if (mest$toolbox == null) {
            return;
        }
        var composites = ((WidgetContainerAccessor) widgets).mest$compositeWidgets();
        if (composites.get("toolbox") == mest$toolbox) {
            if (mest$replaced != null) {
                composites.put("toolbox", mest$replaced);
            } else {
                composites.remove("toolbox");
            }
        }
        mest$toolbox.hide();
        mest$toolbox = null;
        mest$replaced = null;
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
            // The frame stays on AE2's toolbox anchor, shifted left while the custom skin is used.
            return new Point(
                    anchor.getX() - ToolboxChrome.PAD - 1 - ToolboxChrome.shiftX(),
                    anchor.getY() - ToolboxChrome.PAD - 1);
        }
        return new Point(
                screen.getXSize() - 1 - ToolboxChrome.PAD - 1 - ToolboxChrome.shiftX(),
                screen.getYSize() - 84 - ToolboxChrome.PAD - 1);
    }
}
