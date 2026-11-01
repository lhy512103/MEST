package com.lhy.mest.compat.extendedae;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import appeng.client.gui.style.Blitter;

import com.glodblock.github.extendedae.client.button.EPPIcon;
import com.glodblock.github.extendedae.client.button.HighlightButtonSmall;
import com.glodblock.github.extendedae.util.MessageUtil;
import com.lhy.mest.client.panel.PatternAccessChrome;
import com.lhy.mest.network.PatternProviderLoc;

/** Loaded only after the pattern-access factory confirms ExtendedAE is installed. */
public final class ExtendedAePatternAccessChrome implements PatternAccessChrome {
    @Override
    public Blitter buttonBackground(boolean hovered, boolean focused) {
        return hovered ? EPPIcon.TERMINAL_BUTTON_HOVER
                : focused ? EPPIcon.TERMINAL_BUTTON_FOCUS : EPPIcon.TERMINAL_BUTTON;
    }

    @Override
    public Blitter searchModeIcon(boolean inputs, boolean outputs) {
        return inputs && outputs ? EPPIcon.SEARCH_IO
                : inputs ? EPPIcon.SEARCH_INPUT : EPPIcon.SEARCH_OUTPUT;
    }

    @Override
    public ProviderHighlight createHighlight() {
        return new ExtendedHighlight();
    }

    private static final class ExtendedHighlight implements ProviderHighlight {
        private final HighlightButtonSmall button = new HighlightButtonSmall();

        private ExtendedHighlight() {
            button.setMessage(Component.translatable("gui.extendedae.ex_pattern_access_terminal.tooltip.03"));
        }

        @Override
        public Button button() {
            return button;
        }

        @Override
        public void setTarget(PatternProviderLoc location) {
            button.visible = location != null;
            if (location == null) {
                return;
            }
            button.setTarget(location.pos(), location.face(), location.dimension());
            var player = Minecraft.getInstance().player;
            button.setMultiplier(player != null && player.level().dimension().equals(location.dimension())
                    ? Math.sqrt(player.distanceToSqr(Vec3.atCenterOf(location.pos()))) : 1);
            button.setSuccessJob(() -> {
                var viewer = Minecraft.getInstance().player;
                if (viewer != null) {
                    viewer.displayClientMessage(MessageUtil.createEnhancedHighlightMessage(
                            viewer, location.pos(), location.dimension(), "chat.ex_pattern_access_terminal.pos"), false);
                }
            });
        }

        @Override
        public List<Component> tooltip() {
            return button.getTooltipMessage();
        }
    }
}
