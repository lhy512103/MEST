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

import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.inventory.Slot;

import appeng.client.Point;
import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.ICompositeWidget;
import appeng.client.gui.WidgetContainer;
import appeng.client.gui.style.WidgetStyle;
import appeng.core.localization.GuiText;
import appeng.menu.AEBaseMenu;
import appeng.menu.SlotSemantic;
import appeng.menu.SlotSemantics;

import com.lhy.mest.client.MESTScreen;
import com.lhy.mest.client.dock.ToolboxChrome;

/**
 * AE2 draws a fixed 3×3 network-tool panel and stacks any extra upgrade slots on top of it. When
 * the toolbox has more than nine slots (the spliced terminal's built-in inventory), swap that panel
 * for MEST's 3×3 + scrollbar, keeping the same sprite and the same position. Nine-slot vanilla
 * network tools keep AE2's own panel untouched.
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
        if (slots.isEmpty() || !ToolboxChrome.needsScroll(slots)) {
            mest$toolbox = null;
            return;
        }
        Map<String, ICompositeWidget> composites = ((WidgetContainerAccessor) widgets).mest$compositeWidgets();
        ICompositeWidget vanilla = composites.get("toolbox");
        Point position = vanilla != null
                ? new Point(vanilla.getBounds().getX(), vanilla.getBounds().getY())
                : mest$vanillaToolboxPosition(self);
        mest$toolbox = new ToolboxChrome(slots, GuiText.NetworkTool.text());
        mest$toolbox.setPosition(position);
        composites.put("toolbox", mest$toolbox);
    }

    @Inject(method = "repositionSlots", at = @At("RETURN"), remap = false)
    private void mest$repositionToolboxSlots(SlotSemantic semantic, CallbackInfo ci) {
        if (mest$toolbox != null && semantic == SlotSemantics.TOOLBOX) {
            mest$toolbox.placeSlots();
        }
    }

    /**
     * Same anchor AE2 resolves for its own toolbox widget: right/bottom against the GUI image.
     */
    @Unique
    private static Point mest$vanillaToolboxPosition(AEBaseScreen<?> screen) {
        WidgetStyle style = screen.getStyle().getWidget("toolbox");
        if (style != null) {
            return style.resolve(new Rect2i(0, 0, screen.getXSize(), screen.getYSize()));
        }
        return new Point(screen.getXSize() - 2, screen.getYSize() - 90);
    }
}
