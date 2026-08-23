package com.lhy.mest.compat.plus;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

import appeng.client.gui.Icon;
import appeng.client.gui.style.Blitter;
import appeng.client.gui.widgets.IconButton;

import com.extendedae_plus.client.gui.widgets.ScaledTextureButton;
import com.extendedae_plus.client.screen.ProviderSelectScreen;
import com.extendedae_plus.network.ReturnLastPatternC2SPacket;
import com.extendedae_plus.network.ScaleEncodingPatternC2SPacket;
import com.lhy.mest.terminal.MESTMenu;

/**
 * EAEP processing-scale and upload chrome, using Plus widgets/textures/packets and the same
 * offsets Plus injects into {@code ProcessingEncodingPanel} / {@code PatternEncodingTermScreen}.
 */
public final class PlusEncodingChrome {
    private static final ResourceLocation SCALE_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("extendedae_plus", "textures/gui/beizeng.png");
    private static final ResourceLocation SWAP_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("extendedae_plus", "textures/gui/zhu_fu_qie_huan.png");
    private static final ResourceLocation RESTORE_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("extendedae_plus", "textures/gui/huanyuan.png");
    /** AE2 {@code modePanel*} left in pattern_encoding_terminal.json. */
    private static final int MODE_PANEL_LEFT = 9;
    /** AE2 {@code modePanel*} bottom in pattern_encoding_terminal.json. */
    private static final int MODE_PANEL_BOTTOM = 166;

    private final List<AbstractWidget> widgets = new ArrayList<>();
    private final ScaledTextureButton swap;
    private final ScaledTextureButton restore;
    private final ScaledTextureButton mul2;
    private final ScaledTextureButton mul3;
    private final ScaledTextureButton mul5;
    private final ScaledTextureButton div2;
    private final ScaledTextureButton div3;
    private final ScaledTextureButton div5;
    private final IconButton upload;

    public PlusEncodingChrome(MESTMenu menu) {
        mul2 = scaleButton(0, 0, "x2", ScaleEncodingPatternC2SPacket.Operation.MUL2, menu);
        mul3 = scaleButton(16, 0, "x3", ScaleEncodingPatternC2SPacket.Operation.MUL3, menu);
        mul5 = scaleButton(32, 0, "x5", ScaleEncodingPatternC2SPacket.Operation.MUL5, menu);
        div2 = scaleButton(0, 16, "/2", ScaleEncodingPatternC2SPacket.Operation.DIV2, menu);
        div3 = scaleButton(16, 16, "/3", ScaleEncodingPatternC2SPacket.Operation.DIV3, menu);
        div5 = scaleButton(32, 16, "/5", ScaleEncodingPatternC2SPacket.Operation.DIV5, menu);
        swap = standaloneButton(SWAP_TEXTURE,
                Component.translatable("extendedae_plus.tooltip.swap_processing_outputs"),
                ScaleEncodingPatternC2SPacket.Operation.SWAP_OUTPUTS, menu);
        restore = standaloneButton(RESTORE_TEXTURE,
                Component.translatable("extendedae_plus.tooltip.restore_processing_ratio"),
                ScaleEncodingPatternC2SPacket.Operation.RESTORE_RATIO, menu);
        upload = new UploadButton(btn -> {
            if (Screen.hasShiftDown()) {
                PacketDistributor.sendToServer(ReturnLastPatternC2SPacket.INSTANCE);
            } else {
                PlusEncodingUpload.presetSearchKeyForMode(menu.getPatternEncodingMode());
                menu.uploadEncodedPattern(false);
            }
        });
        widgets.add(swap);
        widgets.add(restore);
        widgets.add(mul2);
        widgets.add(mul3);
        widgets.add(mul5);
        widgets.add(div2);
        widgets.add(div3);
        widgets.add(div5);
        widgets.add(upload);
        hideScale();
        upload.visible = false;
    }

    public List<AbstractWidget> widgets() {
        return widgets;
    }

    public void layout(int modePanelX, int modePanelY, int encodeX, int encodeY, boolean visible, boolean processing) {
        boolean showScale = visible && processing && ProviderSelectScreen.isProcessingButtonsEnabled();
        place(swap, modePanelX, modePanelY, 125, 159, showScale);
        place(restore, modePanelX, modePanelY, 100, 159, showScale);
        place(mul2, modePanelX, modePanelY, 125, 149, showScale);
        place(mul3, modePanelX, modePanelY, 125, 138, showScale);
        place(mul5, modePanelX, modePanelY, 125, 127, showScale);
        place(div2, modePanelX, modePanelY, 100, 149, showScale);
        place(div3, modePanelX, modePanelY, 100, 138, showScale);
        place(div5, modePanelX, modePanelY, 100, 127, showScale);

        upload.visible = visible;
        upload.active = visible;
        upload.setWidth(12);
        upload.setHeight(12);
        upload.setX(encodeX - 14);
        upload.setY(encodeY);
    }

    public boolean scaleButtonsVisible() {
        return mul2.visible;
    }

    private void hideScale() {
        swap.setVisibility(false);
        restore.setVisibility(false);
        mul2.setVisibility(false);
        mul3.setVisibility(false);
        mul5.setVisibility(false);
        div2.setVisibility(false);
        div3.setVisibility(false);
        div5.setVisibility(false);
    }

    private static void place(
            ScaledTextureButton button, int modePanelX, int modePanelY, int left, int bottom, boolean show) {
        button.setVisibility(show);
        if (!show) {
            return;
        }
        // Plus: setX(leftPos + left + 1), setY(topPos + imageHeight - bottom)
        // relative to AE2 modePanel (left 9, bottom 166).
        button.setX(modePanelX + left + 1 - MODE_PANEL_LEFT);
        button.setY(modePanelY + MODE_PANEL_BOTTOM - bottom);
    }

    private static ScaledTextureButton scaleButton(
            int srcX, int srcY, String tooltip, ScaleEncodingPatternC2SPacket.Operation op, MESTMenu menu) {
        return new ScaledTextureButton(
                SCALE_TEXTURE, 48, 32, srcX, srcY, 16, 16, 0.375f,
                Component.literal(tooltip),
                btn -> menu.scaleEncodingPattern(codeFor(op)));
    }

    private static ScaledTextureButton standaloneButton(
            ResourceLocation texture, Component tooltip, ScaleEncodingPatternC2SPacket.Operation op, MESTMenu menu) {
        return new ScaledTextureButton(
                texture, 16, 16, 0, 0, 16, 16, 0.375f, tooltip,
                btn -> menu.scaleEncodingPattern(codeFor(op)));
    }

    private static int codeFor(ScaleEncodingPatternC2SPacket.Operation op) {
        return switch (op) {
            case MUL2 -> 2;
            case DIV2 -> -2;
            case MUL3 -> 3;
            case DIV3 -> -3;
            case MUL5 -> 5;
            case DIV5 -> -5;
            case SWAP_OUTPUTS -> 1;
            case RESTORE_RATIO -> 0;
            default -> 0;
        };
    }

    private static final class UploadButton extends IconButton {
        private UploadButton(OnPress onPress) {
            super(onPress);
        }

        @Override
        protected Icon getIcon() {
            return Icon.ARROW_UP;
        }

        @Override
        public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partial) {
            if (!this.visible) {
                return;
            }
            Icon icon = this.getIcon();
            Blitter blitter = icon.getBlitter();
            if (!this.active) {
                blitter.opacity(0.5f);
            }
            this.width = 12;
            this.height = 12;
            RenderSystem.disableDepthTest();
            RenderSystem.enableBlend();
            if (this.isFocused()) {
                guiGraphics.fill(this.getX() - 1, this.getY() - 1, this.getX() + this.width + 1, this.getY(), -1);
                guiGraphics.fill(this.getX() - 1, this.getY(), this.getX(), this.getY() + this.height, -1);
                guiGraphics.fill(this.getX() + this.width, this.getY(), this.getX() + this.width + 1, this.getY() + this.height, -1);
                guiGraphics.fill(this.getX() - 1, this.getY() + this.height, this.getX() + this.width + 1, this.getY() + this.height + 1, -1);
            }
            PoseStack pose = guiGraphics.pose();
            pose.pushPose();
            pose.translate((float) this.getX(), (float) this.getY(), 0.0f);
            pose.scale(0.75f, 0.75f, 1.0f);
            if (!this.isDisableBackground()) {
                Icon.TOOLBAR_BUTTON_BACKGROUND.getBlitter().dest(0, 0).blit(guiGraphics);
            }
            if (Screen.hasShiftDown()) {
                pose.translate(16.0f, 16.0f, 0.0f);
                pose.mulPose(Axis.ZP.rotationDegrees(180.0f));
            }
            blitter.dest(0, 0).blit(guiGraphics);
            pose.popPose();
            RenderSystem.enableDepthTest();
        }

        @Override
        public Rect2i getTooltipArea() {
            return new Rect2i(this.getX(), this.getY(), 12, 12);
        }

        @Override
        public List<Component> getTooltipMessage() {
            return List.of(Component.translatable(Screen.hasShiftDown()
                    ? "extendedae_plus.button.return_last_pattern"
                    : "extendedae_plus.button.choose_provider"));
        }
    }
}
