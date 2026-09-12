package com.lhy.mest.mixin;

import java.util.List;
import java.util.Map;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.inventory.Slot;

import appeng.client.Point;
import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.ICompositeWidget;
import appeng.client.gui.WidgetContainer;
import appeng.menu.AEBaseMenu;
import appeng.menu.SlotSemantic;
import appeng.menu.SlotSemantics;

import com.lhy.mest.client.MESTScreen;
import com.lhy.mest.client.dock.ToolboxChrome;

/**
 * When a network tool has more than 9 upgrade slots, hide AE2's fixed 3×3 toolbox chrome and
 * swap in MEST's 3×3 + scrollbar. Vanilla 9-slot tools are left untouched.
 */
@Mixin(AEBaseScreen.class)
public abstract class NetworkToolboxScreenMixin {
    @Shadow
    @Final
    protected WidgetContainer widgets;

    @Unique
    private ToolboxChrome mest$toolbox;

    @Inject(method = "init", at = @At("RETURN"))
    private void mest$installScrollingToolbox(CallbackInfo ci) {
        AEBaseScreen<?> self = (AEBaseScreen<?>) (Object) this;
        if (self instanceof MESTScreen || !(self.getMenu() instanceof AEBaseMenu menu)) {
            return;
        }
        List<Slot> slots = menu.getSlots(SlotSemantics.TOOLBOX);
        if (slots.isEmpty()) {
            mest$toolbox = null;
            return;
        }
        Map<String, ICompositeWidget> composites = ((WidgetContainerAccessor) widgets).mest$compositeWidgets();
        ICompositeWidget previous = composites.get("toolbox");
        if (!ToolboxChrome.needsScroll(slots) && previous != null) {
            mest$toolbox = null;
            return;
        }
        int x = previous != null ? previous.getBounds().getX() : self.getXSize() - 21;
        int y = previous != null ? previous.getBounds().getY() : self.getYSize() - 90;
        mest$toolbox = new ToolboxChrome(slots, appeng.core.localization.GuiText.NetworkTool.text());
        mest$toolbox.setPosition(new Point(x, y));
        composites.put("toolbox", mest$toolbox);
    }

    @Inject(method = "repositionSlots", at = @At("RETURN"), remap = false)
    private void mest$repositionToolboxSlots(SlotSemantic semantic, CallbackInfo ci) {
        if (mest$toolbox != null && semantic == SlotSemantics.TOOLBOX) {
            mest$toolbox.placeSlots();
        }
    }
}
