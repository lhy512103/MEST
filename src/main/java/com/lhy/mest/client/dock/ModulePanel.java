package com.lhy.mest.client.dock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;

import appeng.client.gui.Icon;
import appeng.client.gui.style.BackgroundGenerator;
import appeng.client.gui.style.Blitter;
import appeng.core.AppEng;

import net.neoforged.fml.ModList;

import com.glodblock.github.extendedae.client.button.EPPIcon;

import com.lhy.mest.client.MestGuiIcons;
import com.lhy.mest.client.dock.model.DockAxis;
import com.lhy.mest.client.dock.model.DockRect;
import com.lhy.mest.client.dock.model.LayoutProjection;

/**
 * Base class for a floating, draggable, resizable module panel.
 *
 * <p>A panel owns a rectangle on screen ({@link #x},{@link #y},{@link #width},{@link #height}) made of
 * a title bar (drag handle) and a content area. It also owns a set of menu {@link Slot}s; the panel
 * lays them out in <em>content-local</em> coordinates and the managing screen translates those into
 * absolute screen coordinates (since {@code leftPos}/{@code topPos} are pinned to 0).
 *
 * <p>The docking engine is intentionally decoupled from concrete modules: subclasses only implement
 * content rendering, slot layout and a stable {@link #id()} used for layout persistence.
 */
public abstract class ModulePanel {
    public static final int TITLE_BAR_HEIGHT = 17;
    public static final int CONTENT_PADDING = 7;
    public static final int RESIZE_HANDLE = 10;

    // Panel bounds in absolute screen coordinates.
    public int x;
    public int y;
    public int width;
    public int height;
    public boolean visible = true;
    // Compatibility flag for content-only hosts. The recursive dock keeps leaves unhosted so every
    // module retains its own title bar.
    public boolean hosted;
    /** True when this leaf shares a window with at least one other visible module. */
    public boolean spliced;
    /** Shared window bounds while {@link #spliced}; otherwise null. */
    public DockRect splicedWindow;
    /** Extra right padding so a sibling does not draw into the ME scroller gutter. */
    public int contentRightInset;
    /** Non-negative nudge of the content block inside this leaf. */
    public int contentOffsetX;
    public int contentOffsetY;
    /** True while the layout editor is nudging this leaf inside a shell section. */
    public boolean contentEditing;
    /** False when another visible leaf in the same window sits to the right. */
    public boolean rightmostInWindow = true;
    /** Reserved inner width on the right of this leaf (ME scroller when a sibling sits to the right). */
    public int preferredContentRightInset() {
        return 0;
    }

    private final List<Slot> ownedSlots = new ArrayList<>();
    private final Set<Slot> ownedSlotSet = Collections.newSetFromMap(new IdentityHashMap<>());

    /** Stable identifier, used as the key for layout persistence and module recreation. */
    public abstract String id();

    /** Human-readable title shown in the title bar. */
    public abstract Component title();

    /** Minimum panel size; resizing cannot go below this. */
    public int minWidth() {
        return 60;
    }

    public int minHeight() {
        return TITLE_BAR_HEIGHT + CONTENT_PADDING + 18;
    }

    /** Initial/default size when no saved layout exists. */
    public abstract int defaultWidth();

    public abstract int defaultHeight();

    protected List<Slot> ownedSlots() {
        return ownedSlots;
    }

    protected void registerSlot(Slot slot) {
        ownedSlots.add(slot);
        ownedSlotSet.add(slot);
    }

    protected static void placeSlot(Slot slot, int screenX, int screenY) {
        slot.x = screenX;
        slot.y = screenY;
    }

    protected static void hideSlot(Slot slot) {
        slot.x = -9999;
        slot.y = -9999;
    }

    public static int slotScreenX(Slot slot) {
        return slot.x;
    }

    public static int slotScreenY(Slot slot) {
        return slot.y;
    }

    // --- Geometry helpers -------------------------------------------------

    public int contentLeft() {
        return x + CONTENT_PADDING + contentOffsetX;
    }

    public int contentTop() {
        return y + (drawsTitleBar() ? TITLE_BAR_HEIGHT : CONTENT_PADDING) + contentOffsetY;
    }

    public int contentWidth() {
        return Math.max(0, width - 2 * CONTENT_PADDING - contentRightInset);
    }

    public int contentHeight() {
        int top = drawsTitleBar() ? TITLE_BAR_HEIGHT : CONTENT_PADDING;
        return height - top - contentBottomPad();
    }

    /**
     * Intrinsic content size used when nudging inside a stretched shell section. Expanding
     * modules report the current content size, so they have no leftover slack to drag through.
     */
    public int preferredContentWidth() {
        if (fillsContentArea()) {
            return contentWidth();
        }
        return Math.max(0, defaultWidth() - 2 * CONTENT_PADDING - preferredContentRightInset());
    }

    public int preferredContentHeight() {
        if (fillsContentArea()) {
            return contentHeight();
        }
        int top = drawsTitleBar() ? TITLE_BAR_HEIGHT : CONTENT_PADDING;
        return Math.max(0, defaultHeight() - top - CONTENT_PADDING);
    }

    public int contentSlackX() {
        return Math.max(0, contentWidth() - preferredContentWidth());
    }

    public int contentSlackY() {
        return Math.max(0, contentHeight() - preferredContentHeight());
    }

    /** True when this panel grows its widgets to fill the section, leaving no nudge slack. */
    public boolean fillsContentArea() {
        return false;
    }

    /**
     * Spliced sections keep vanilla-tight spacing: only the bottom-most leaf in a window
     * still reserves the AE2 7px bottom inset.
     */
    private int contentBottomPad() {
        if (splicedWindow != null && y + height < splicedWindow.bottom()) {
            return 0;
        }
        return CONTENT_PADDING;
    }

    /** True when leftover vertical space should go to this leaf instead of a sibling. */
    public boolean expandsVertically() {
        return false;
    }

    /** Standalone windows and composite sections keep a title strip; compact rails do not. */
    protected boolean drawsTitleBar() {
        return !hosted;
    }

    public boolean contains(double mx, double my) {
        return mx >= x && mx < x + width && my >= y && my < y + height;
    }

    /**
     * Extra width to the right of the framed panel that still belongs to this module for
     * hit-testing (encoding mode tabs, ME scroller well). Not part of the window used for
     * return-centering.
     */
    public int outsideHitWidth() {
        return 0;
    }

    /**
     * True when this module is chrome that should sit outside a spliced window
     * (side toolbar), not be painted inside the shared frame.
     */
    public boolean isOutsideChrome() {
        return false;
    }

    /** False when this module must stay a standalone window and cannot join a spliced split. */
    public boolean canSplice() {
        return true;
    }

    /**
     * True for right-edge chrome whose {@code vertical_buttons_bg} well may be joined with an
     * adjacent panel: ME/pattern-access scroll rails, and encoding mode tabs when they hang outside.
     */
    public boolean hasJoinableOutsideRail() {
        return false;
    }

    /**
     * When several right-edge scroll rails stack in one window, only the top panel paints
     * the shared 9-slice background; others keep their own track/handle.
     */
    public boolean drawOutsideRail = true;
    public int joinedRailY;
    public int joinedRailH;

    public void resetJoinedRail() {
        drawOutsideRail = hasJoinableOutsideRail();
        joinedRailY = y;
        joinedRailH = height;
    }

    /** Extra width to the left of the framed panel (ME terminal sidebar). */
    public int outsideHitLeftWidth() {
        return 0;
    }

    /** Extra height above the framed panel (crafting-status tab). */
    public int outsideHitTop() {
        return 0;
    }

    private static final int PIN_SIZE = 12;
    private static final int CHROME_BUTTON_GAP = 2;
    private boolean pinVisible;
    private boolean pinned;

    public void setPinControl(boolean visible, boolean pinned) {
        this.pinVisible = visible;
        this.pinned = pinned;
    }

    public boolean pinVisible() {
        return pinVisible && drawsTitleBar() && !contentEditing;
    }

    public boolean contentChromeVisible() {
        return contentEditing && drawsTitleBar();
    }

    public boolean pinned() {
        return pinned;
    }

    public int pinButtonX() {
        return x + width - PIN_SIZE - 4;
    }

    public int pinButtonY() {
        return y + 3;
    }

    public int closeButtonX() {
        return pinButtonX();
    }

    public int closeButtonY() {
        return pinButtonY();
    }

    public int resetButtonX() {
        return closeButtonX() - PIN_SIZE - CHROME_BUTTON_GAP;
    }

    public int resetButtonY() {
        return pinButtonY();
    }

    public boolean inPinButton(double mx, double my) {
        return pinVisible() && inChromeButton(mx, my, pinButtonX(), pinButtonY());
    }

    public boolean inCloseButton(double mx, double my) {
        return contentChromeVisible() && inChromeButton(mx, my, closeButtonX(), closeButtonY());
    }

    public boolean inResetButton(double mx, double my) {
        return contentChromeVisible() && inChromeButton(mx, my, resetButtonX(), resetButtonY());
    }

    public Component titleBarTooltip(double mx, double my) {
        if (inPinButton(mx, my)) {
            return Component.translatable(pinned()
                    ? "gui.mesplicedterminal.unpin_panel"
                    : "gui.mesplicedterminal.pin_panel");
        }
        if (inCloseButton(mx, my)) {
            return Component.translatable("gui.mesplicedterminal.content_edit.close");
        }
        if (inResetButton(mx, my)) {
            return Component.translatable("gui.mesplicedterminal.content_edit.reset");
        }
        return null;
    }

    private static boolean inChromeButton(double mx, double my, int px, int py) {
        return mx >= px && mx < px + PIN_SIZE && my >= py && my < py + PIN_SIZE;
    }

    /**
     * Drawn before the panel frame so overlapping chrome (encoding tabs) tucks under the window.
     */
    public void renderUnderlay(GuiGraphics g, Font font, int mouseX, int mouseY, float partialTicks) {
    }

    public boolean inTitleBar(double mx, double my) {
        if (!drawsTitleBar()) {
            return false;
        }
        return mx >= x && mx < x + width && my >= y && my < y + TITLE_BAR_HEIGHT
                && !inTitleBarControls(mx, my);
    }

    /**
     * Title-bar widgets (search field, etc.) that must not start a window drag.
     */
    public boolean inTitleBarControls(double mx, double my) {
        return inPinButton(mx, my) || inCloseButton(mx, my) || inResetButton(mx, my);
    }

    public boolean inResizeHandle(double mx, double my) {
        return mx >= x + width - RESIZE_HANDLE && mx < x + width
                && my >= y + height - RESIZE_HANDLE && my < y + height;
    }

    /**
     * Width reserved on the right side of the title bar for controls supplied by a concrete panel.
     *
     * <p>The dock draws the resize affordance at floating-root level, so this inset only concerns
     * title-bar controls (for example the three ME sorting buttons). Subclasses can increase it when
     * they place controls over the title bar.
     */
    protected int titleRightInset() {
        if (contentChromeVisible()) {
            return 8 + 2 * PIN_SIZE + CHROME_BUTTON_GAP;
        }
        return pinVisible() ? 28 + PIN_SIZE : 28;
    }

    protected int titleTextOffsetX() {
        return 0;
    }

    // --- Lifecycle hooks --------------------------------------------------

    /**
     * Lay out this panel's slots in absolute screen coordinates based on the current content area.
     * Called once on init and again whenever the panel moves or resizes. Subclasses position their
     * owned slots by writing {@code slot.x}/{@code slot.y} directly.
     */
    public abstract void layoutSlots();

    /** Render panel-owned content that must appear below slot item contents. */
    public void renderBackgroundContent(GuiGraphics g, Font font, int mouseX, int mouseY, float partialTicks) {
    }

    /** Render panel-owned content that must appear above slot item contents. */
    public void renderForegroundContent(GuiGraphics g, Font font, int mouseX, int mouseY, float partialTicks) {
    }

    /**
     * Render this panel's owned slot icons immediately after the panel's own frame + background content, so that a
     * top panel's frame paints over a lower panel's slot icons (z-order correct). Slots moved off-screen (hidden
     * panel / inactive encoding mode) are skipped.
     *
     * <p>The hover highlight is <em>not</em> drawn here. The hosting screen gates the vanilla per-slot highlight via
     * {@code isHovering} so only the topmost panel's slot under the cursor is highlighted — no cross-panel bleed.
     *
     * <p>Per-slot icon drawing is delegated to {@code renderer} (the hosting screen), which reuses AE2's existing
     * RepoSlot / AppEngSlot rendering — no logic is duplicated here.
     */
    public void renderSlots(GuiGraphics g, PanelSlotRenderer renderer) {
        for (Slot s : ownedSlots()) {
            if (s.x <= -1000 || s.y <= -1000) {
                continue;
            }
            renderer.drawPanelSlot(g, s);
        }
    }

    // --- Optional per-panel scroll interaction ----------------------------

    /** Handle a click on panel-owned widgets. Default: not consumed. */
    public boolean mouseClicked(double mx, double my, int button) {
        return false;
    }

    /**
     * Handle a scroll-wheel event routed to this panel (the topmost leaf under the cursor).
     * Default: not consumed.
     */
    public boolean mouseScrolled(double mx, double my, double scrollY) {
        return false;
    }

    /** Begin a scrollbar drag (or page-jump). Returns true if consumed. Default: no scrollbar. */
    public boolean scrollbarPressed(double mx, double my) {
        return false;
    }

    /** Continue a scrollbar drag started by {@link #scrollbarPressed}. Returns true if consumed. */
    public boolean scrollbarDragged(double mx, double my) {
        return false;
    }

    /** End any scrollbar drag. */
    public void scrollbarReleased() {
    }

    /** True while this panel's scrollbar thumb is being dragged. */
    public boolean scrollbarDragging() {
        return false;
    }

    /** True if this panel owns the given slot (identity check). */
    public boolean ownsSlot(Slot slot) {
        return ownedSlotSet.contains(slot);
    }

    // --- Frame rendering (shared chrome) ----------------------------------

    // AE2 terminal palette (see screens/common/palette.json and terminal.png).
    public static final int COLOR_PANEL = 0xFFC8CAD2;
    public static final int COLOR_LIGHT = 0xFFE5E7ED;
    public static final int COLOR_DARK = 0xFF777B8C;
    public static final int COLOR_MUTED = 0xFF878FA5;
    public static final int COLOR_TITLE_TEXT = 0xFF413F54;
    /** Faint 1px rule separating sections inside a unified outer shell. */
    public static final int SECTION_RULE_COLOR = 0x66777B8C;
    /** The same rule while the pointer sits on its divider. */
    public static final int SECTION_RULE_HOVER_COLOR = 0xCCACE9FF;
    private static final int TITLE_LEFT_INSET = 8;

    private static final Blitter TEXT_FIELD = Blitter.texture("guis/text_field.png", 128, 128);
    private static final Blitter CRAFTING_ARROW = Blitter.texture("guis/crafting.png", 256, 256)
            .src(83, 109, 43, 10);

    public static final int EDGE_TOP = 1;
    public static final int EDGE_BOTTOM = 2;
    public static final int EDGE_LEFT = 4;
    public static final int EDGE_RIGHT = 8;
    private static final int BG_BORDER = 4;
    private static final int BG_TILE = 248;
    private static final Blitter BG = Blitter.texture("guis/background.png", 256, 256);

    public void renderFrame(GuiGraphics g, Font font, int mouseX, int mouseY, float partialTicks) {
        renderFrame(g, font, mouseX, mouseY, partialTicks, 0);
    }

    public void renderFrame(
            GuiGraphics g, Font font, int mouseX, int mouseY, float partialTicks, int sharedEdges) {
        if (hosted || !drawsTitleBar()) {
            return;
        }
        // Drop-shadow. Skip the right edge when a panel parks outside chrome (encoding tabs)
        // there — otherwise the 2px shade paints over those widgets.
        if ((sharedEdges & EDGE_BOTTOM) == 0) {
            g.fill(x + 2, y + height, x + width + 2, y + height + 2, 0x55000000);
        }
        if ((sharedEdges & EDGE_RIGHT) == 0 && outsideHitWidth() <= 0) {
            g.fill(x + width, y + 2, x + width + 2, y + height + 2, 0x55000000);
        }
        drawGeneratedBackground(g, x, y, width, height, sharedEdges);
        renderTitleStrip(g, font, x, y, width, mouseX, mouseY);
    }

    /**
     * AE2 {@code BackgroundGenerator} with optional hidden edges so spliced leaves share one
     * interior instead of painting a second 4px bevel over the join.
     */
    public static void drawGeneratedBackground(
            GuiGraphics g, int x, int y, int width, int height, int hiddenEdges) {
        if (width < 8 || height < 8) {
            return;
        }
        boolean hideTop = (hiddenEdges & EDGE_TOP) != 0;
        boolean hideBottom = (hiddenEdges & EDGE_BOTTOM) != 0;
        boolean hideLeft = (hiddenEdges & EDGE_LEFT) != 0;
        boolean hideRight = (hiddenEdges & EDGE_RIGHT) != 0;
        int innerX = hideLeft ? x : x + BG_BORDER;
        int innerY = hideTop ? y : y + BG_BORDER;
        int innerW = width - (hideLeft ? 0 : BG_BORDER) - (hideRight ? 0 : BG_BORDER);
        int innerH = height - (hideTop ? 0 : BG_BORDER) - (hideBottom ? 0 : BG_BORDER);
        int right = x + width;
        int bottom = y + height;
        if (!hideTop && !hideLeft) {
            BG.copy().src(0, 0, BG_BORDER, BG_BORDER).dest(x, y).blit(g);
        }
        if (!hideTop && !hideRight) {
            BG.copy().src(252, 0, BG_BORDER, BG_BORDER).dest(right - BG_BORDER, y).blit(g);
        }
        if (!hideBottom && !hideLeft) {
            BG.copy().src(0, 252, BG_BORDER, BG_BORDER).dest(x, bottom - BG_BORDER).blit(g);
        }
        if (!hideBottom && !hideRight) {
            BG.copy().src(252, 252, BG_BORDER, BG_BORDER).dest(right - BG_BORDER, bottom - BG_BORDER).blit(g);
        }
        for (int cx = 0; cx < innerW; cx += BG_TILE) {
            int tileW = Math.min(BG_TILE, innerW - cx);
            if (!hideTop) {
                BG.copy().src(BG_BORDER, 0, tileW, BG_BORDER).dest(innerX + cx, y).blit(g);
            }
            if (!hideBottom) {
                BG.copy().src(BG_BORDER, 252, tileW, BG_BORDER).dest(innerX + cx, bottom - BG_BORDER).blit(g);
            }
            for (int cy = 0; cy < innerH; cy += BG_TILE) {
                int tileH = Math.min(BG_TILE, innerH - cy);
                BG.copy().src(BG_BORDER, BG_BORDER, tileW, tileH).dest(innerX + cx, innerY + cy).blit(g);
            }
        }
        for (int cy = 0; cy < innerH; cy += BG_TILE) {
            int tileH = Math.min(BG_TILE, innerH - cy);
            if (!hideLeft) {
                BG.copy().src(0, BG_BORDER, BG_BORDER, tileH).dest(x, innerY + cy).blit(g);
            }
            if (!hideRight) {
                BG.copy().src(252, BG_BORDER, BG_BORDER, tileH).dest(right - BG_BORDER, innerY + cy).blit(g);
            }
        }
    }

    /**
     * Inner title used when this leaf is painted inside a unified composite window.
     */
    public void renderSectionHeader(GuiGraphics g, Font font, int mouseX, int mouseY) {
        if (!drawsTitleBar()) {
            return;
        }
        renderTitleStrip(g, font, x, y, width, mouseX, mouseY);
    }

    /**
     * One generated AE2 window for a spliced root. Leaves then only draw section titles + content.
     *
     * <p>Shared by the unified and outer-shell splice modes: in both cases the whole composite is
     * covered by a single background, which is what removes any possibility of a seam.
     */
    public static void renderRootChrome(GuiGraphics g, DockRect bounds, boolean skipRightShadow) {
        if (bounds == null || bounds.width() < 2 || bounds.height() < 2) {
            return;
        }
        int x = bounds.x();
        int y = bounds.y();
        int w = bounds.width();
        int h = bounds.height();
        g.fill(x + 2, y + h, x + w + 2, y + h + 2, 0x55000000);
        if (!skipRightShadow) {
            g.fill(x + w, y + 2, x + w + 2, y + h + 2, 0x55000000);
        }
        BackgroundGenerator.draw(w, h, g, x, y);
    }

    /**
     * Draws the 1px rules that separate sections inside a unified outer shell.
     *
     * <p>The shell mode covers the whole composite with one background, so these rules - not a
     * per-leaf bevel - are what convey section boundaries. A rounding error can only shift a rule
     * by a pixel; it can never expose a doubled border the way hidden-edge splicing can.
     */
    public static void drawSectionRules(
            GuiGraphics g,
            List<LayoutProjection.DividerPlacement> dividers,
            String hoveredSplitNodeId) {
        if (dividers == null || dividers.isEmpty()) {
            return;
        }
        for (LayoutProjection.DividerPlacement divider : dividers) {
            DockRect bounds = divider.bounds();
            if (bounds == null) {
                continue;
            }
            boolean hovered = hoveredSplitNodeId != null
                    && hoveredSplitNodeId.equals(divider.splitNodeId());
            int color = hovered ? SECTION_RULE_HOVER_COLOR : SECTION_RULE_COLOR;
            if (divider.axis() == DockAxis.HORIZONTAL) {
                g.fill(bounds.x(), bounds.y(), bounds.x() + 1, bounds.bottom(), color);
            } else {
                g.fill(bounds.x(), bounds.y(), bounds.right(), bounds.y() + 1, color);
            }
        }
    }

    private void renderTitleStrip(GuiGraphics g, Font font, int left, int top, int barWidth, int mouseX, int mouseY) {
        String clippedTitle = font.plainSubstrByWidth(
                title().getString(), Math.max(0, barWidth - TITLE_LEFT_INSET - titleRightInset()));
        g.drawString(font, clippedTitle, left + TITLE_LEFT_INSET + titleTextOffsetX(), top + 6, COLOR_TITLE_TEXT, false);
        if (contentChromeVisible()) {
            drawChromeButton(g, resetButtonX(), resetButtonY(), inResetButton(mouseX, mouseY), ChromeGlyph.RESET);
            drawChromeButton(g, closeButtonX(), closeButtonY(), inCloseButton(mouseX, mouseY), ChromeGlyph.CLOSE);
        } else if (pinVisible()) {
            int px = pinButtonX();
            int py = pinButtonY();
            boolean hovered = inPinButton(mouseX, mouseY);
            int yOffset = hovered ? 1 : 0;
            if (ModList.get().isLoaded("extendedae")) {
                Blitter background = hovered
                        ? EPPIcon.TERMINAL_BUTTON_HOVER
                        : EPPIcon.TERMINAL_BUTTON;
                background.dest(px, py + yOffset, PIN_SIZE, PIN_SIZE).zOffset(2).blit(g);
            } else {
                // ExtendedAE supplies a tasteful button sprite; without it, fall back to a flat dark
                // fill that matches the title bar so the pin glyph still reads as a button.
                int bg = hovered ? 0xFF4A4A4A : 0xFF2E2E2E;
                g.fill(px, py + yOffset, px + PIN_SIZE, py + yOffset + PIN_SIZE, bg);
            }
            MestGuiIcons.blit(g, 12, 1, px, py + yOffset, PIN_SIZE, PIN_SIZE);
        }
    }

    private void drawChromeButton(GuiGraphics g, int px, int py, boolean hovered, ChromeGlyph glyph) {
        int yOffset = hovered ? 1 : 0;
        if (ModList.get().isLoaded("extendedae")) {
            Blitter background = hovered
                    ? EPPIcon.TERMINAL_BUTTON_HOVER
                    : EPPIcon.TERMINAL_BUTTON;
            background.dest(px, py + yOffset, PIN_SIZE, PIN_SIZE).zOffset(2).blit(g);
        } else {
            int bg = hovered ? 0xFF4A4A4A : 0xFF2E2E2E;
            g.fill(px, py + yOffset, px + PIN_SIZE, py + yOffset + PIN_SIZE, bg);
        }
        int ix = px;
        int iy = py + yOffset;
        if (glyph == ChromeGlyph.CLOSE) {
            // layout_preset_icons.png row 4 col 2 (16, 48)
            MestGuiIcons.blit(g, 1, 3, ix, iy, PIN_SIZE, PIN_SIZE);
        } else {
            // AE2 states.png last 16px row, first cell (0, 240)
            Icon.SCHEDULING_DEFAULT.getBlitter()
                    .dest(ix, iy, PIN_SIZE, PIN_SIZE)
                    .zOffset(3)
                    .blit(g);
        }
    }

    private enum ChromeGlyph {
        CLOSE,
        RESET
    }

    /**
     * Dashed centre guides used while nudging a content block inside a shell section.
     * Guides use the same inner content rectangle as {@link #contentSlackX()} / {@link #contentSlackY()},
     * otherwise the section midline sits in the reserved padding and never meets the content block.
     */
    public void renderContentGuides(GuiGraphics g, boolean snapX, boolean snapY) {
        int innerLeft = x + CONTENT_PADDING;
        int innerTop = y + (drawsTitleBar() ? TITLE_BAR_HEIGHT : CONTENT_PADDING);
        int innerRight = innerLeft + contentWidth();
        int innerBottom = innerTop + contentHeight();
        int innerW = Math.max(0, innerRight - innerLeft);
        int innerH = Math.max(0, innerBottom - innerTop);
        int sectionMidX = innerLeft + innerW / 2;
        int sectionMidY = innerTop + innerH / 2;
        int contentW = Math.min(preferredContentWidth(), contentWidth());
        int contentH = Math.min(preferredContentHeight(), contentHeight());
        int contentMidX = contentLeft() + contentW / 2;
        int contentMidY = contentTop() + contentH / 2;
        int sectionColor = 0x66ACE9FF;
        int contentColor = 0xBBACE9FF;
        int snapColor = 0xFFE8F7FF;
        drawDashedVLine(g, sectionMidX, innerTop, innerBottom, snapX ? snapColor : sectionColor);
        drawDashedHLine(g, sectionMidY, innerLeft, innerRight, snapY ? snapColor : sectionColor);
        if (!snapX) {
            drawDashedVLine(g, contentMidX, innerTop, innerBottom, contentColor);
        }
        if (!snapY) {
            drawDashedHLine(g, contentMidY, innerLeft, innerRight, contentColor);
        }
    }

    private static void drawDashedHLine(GuiGraphics g, int y, int x0, int x1, int color) {
        if (x1 <= x0) {
            return;
        }
        for (int x = x0; x < x1; x += 4) {
            g.fill(x, y, Math.min(x + 2, x1), y + 1, color);
        }
    }

    private static void drawDashedVLine(GuiGraphics g, int x, int y0, int y1, int color) {
        if (y1 <= y0) {
            return;
        }
        for (int y = y0; y < y1; y += 4) {
            g.fill(x, y, x + 1, Math.min(y + 2, y1), color);
        }
    }

    /**
     * Draw the one resize affordance owned by a floating root.
     *
     * <p>Individual leaves still expose {@link #inResizeHandle(double, double)} for compatibility,
     * but a composite root must never draw one grip per leaf. The dock therefore invokes this helper
     * once with the root rectangle after all leaf frames have been rendered.
     */
    public static void renderResizeGrip(GuiGraphics g, DockRect bounds) {
        if (bounds == null || bounds.width() < 2 || bounds.height() < 2) {
            return;
        }
        int right = bounds.right();
        int bottom = bounds.bottom();
        for (int row = 0; row < 3; row++) {
            int dots = 3 - row;
            for (int col = 0; col < dots; col++) {
                int x = right - 3 - col * 3;
                int y = bottom - 3 - row * 3;
                g.fill(x, y, x + 2, y + 2, COLOR_DARK);
            }
        }
    }

    /** Draw a single AE2-style recessed 18x18 slot whose top-left is at (px,py) (the 16x16 item area
     *  sits at px+1,py+1). */
    public static void drawSlot(GuiGraphics g, int px, int py) {
        appeng.client.gui.Icon.SLOT_BACKGROUND.getBlitter().dest(px, py).blit(g);
    }

    /** Draw AE2's native 12px text-field background at an arbitrary width. */
    public static void drawTextField(GuiGraphics g, int x, int y, int width, boolean focused) {
        int srcY = focused ? 24 : 0;
        TEXT_FIELD.src(0, srcY, 1, 12).dest(x, y).blit(g);
        TEXT_FIELD.src(1, srcY, 126, 12).dest(x + 1, y, Math.max(0, width - 2), 12).blit(g);
        TEXT_FIELD.src(127, srcY, 1, 12).dest(x + width - 1, y).blit(g);
    }

    /** Draw a text button with the same sprites and colors as AE2Button. */
    public static void drawButton(GuiGraphics g, Font font, Component label,
            int x, int y, int width, int height, boolean highlighted) {
        g.blitSprite(AppEng.makeId(highlighted ? "button_highlighted" : "button"), x, y, width, height);
        String text = font.plainSubstrByWidth(label.getString(), Math.max(0, width - 6));
        int color = highlighted ? 0xFF517497 : 0xFFF2F2F2;
        g.drawString(font, text, x + (width - font.width(text)) / 2, y + (height - 8) / 2, color, false);
    }

    /** Draw the arrow artwork used between the grid and output in AE2's crafting terminal. */
    public static void drawCraftingArrow(GuiGraphics g, int x, int y) {
        CRAFTING_ARROW.dest(x, y, 20, 7).blit(g);
    }

    public void clampSize() {
        if (width < minWidth()) {
            width = minWidth();
        }
        if (height < minHeight()) {
            height = minHeight();
        }
    }

    /** Functional callback used by {@link #renderSlots} to delegate per-slot icon drawing back to the hosting
     *  screen, which reuses AE2's existing RepoSlot / AppEngSlot rendering. */
    @FunctionalInterface
    public interface PanelSlotRenderer {
        void drawPanelSlot(GuiGraphics g, Slot slot);
    }
}
