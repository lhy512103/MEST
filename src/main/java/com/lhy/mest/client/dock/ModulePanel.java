package com.lhy.mest.client.dock;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;

import appeng.client.gui.style.BackgroundGenerator;
import appeng.client.gui.style.Blitter;
import appeng.core.AppEng;

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
    public static final int TITLE_BAR_HEIGHT = 18;
    public static final int CONTENT_PADDING = 7;
    public static final int RESIZE_HANDLE = 6;

    // Panel bounds in absolute screen coordinates.
    public int x;
    public int y;
    public int width;
    public int height;
    public boolean visible = true;
    // Compatibility flag for content-only hosts. The recursive dock keeps leaves unhosted so every
    // module retains its own title bar.
    public boolean hosted;

    private final List<Slot> ownedSlots = new ArrayList<>();

    /** Stable identifier, used as the key for layout persistence and module recreation. */
    public abstract String id();

    /** Human-readable title shown in the title bar. */
    public abstract Component title();

    /** Minimum panel size; resizing cannot go below this. */
    public int minWidth() {
        return 60;
    }

    public int minHeight() {
        return TITLE_BAR_HEIGHT + 2 * CONTENT_PADDING + 18;
    }

    /** Initial/default size when no saved layout exists. */
    public abstract int defaultWidth();

    public abstract int defaultHeight();

    protected List<Slot> ownedSlots() {
        return ownedSlots;
    }

    protected void registerSlot(Slot slot) {
        ownedSlots.add(slot);
    }

    // --- Geometry helpers -------------------------------------------------

    public int contentLeft() {
        return x + CONTENT_PADDING;
    }

    public int contentTop() {
        // Hosted children have no title bar — their whole rect is content (composite draws the chrome).
        return hosted ? y + CONTENT_PADDING : y + TITLE_BAR_HEIGHT + CONTENT_PADDING;
    }

    public int contentWidth() {
        return width - 2 * CONTENT_PADDING;
    }

    public int contentHeight() {
        // Hosted children have no title bar — only vertical content padding applies.
        return hosted ? height - 2 * CONTENT_PADDING : height - TITLE_BAR_HEIGHT - 2 * CONTENT_PADDING;
    }

    public boolean contains(double mx, double my) {
        return mx >= x && mx < x + width && my >= y && my < y + height;
    }

    public boolean inTitleBar(double mx, double my) {
        return mx >= x && mx < x + width && my >= y && my < y + TITLE_BAR_HEIGHT;
    }

    public boolean inResizeHandle(double mx, double my) {
        return mx >= x + width - RESIZE_HANDLE && mx < x + width
                && my >= y + height - RESIZE_HANDLE && my < y + height;
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

    /** True if this panel owns the given slot (identity check). */
    public boolean ownsSlot(Slot slot) {
        for (Slot s : ownedSlots) {
            if (s == slot) {
                return true;
            }
        }
        return false;
    }

    // --- Frame rendering (shared chrome) ----------------------------------

    // AE2 terminal palette (see screens/common/palette.json and terminal.png).
    public static final int COLOR_PANEL = 0xFFC8CAD2;
    public static final int COLOR_LIGHT = 0xFFE5E7ED;
    public static final int COLOR_DARK = 0xFF777B8C;
    public static final int COLOR_MUTED = 0xFF878FA5;
    public static final int COLOR_TITLE_TEXT = 0xFF413F54;

    private static final Blitter TEXT_FIELD = Blitter.texture("guis/text_field.png", 128, 128);
    private static final Blitter CRAFTING_ARROW = Blitter.texture("guis/crafting.png", 256, 256)
            .src(83, 109, 43, 10);

    public void renderFrame(GuiGraphics g, Font font, int mouseX, int mouseY, float partialTicks) {
        if (hosted) {
            // Hosted children are framed by their composite; they only draw content.
            return;
        }
        // Use the exact scalable background renderer used by AE2's generated screens.
        g.fill(x + 2, y + height, x + width + 2, y + height + 2, 0x55000000);
        g.fill(x + width, y + 2, x + width + 2, y + height + 2, 0x55000000);
        BackgroundGenerator.draw(width, height, g, x, y);

        // AE2 headers are part of the light dialog surface instead of a dark desktop-window bar.
        String clippedTitle = font.plainSubstrByWidth(title().getString(), Math.max(0, width - 28));
        g.drawString(font, clippedTitle, x + 8, y + 6, COLOR_TITLE_TEXT, false);
        g.fill(x + 5, y + TITLE_BAR_HEIGHT - 2, x + width - 5, y + TITLE_BAR_HEIGHT - 1, COLOR_DARK);
        g.fill(x + 5, y + TITLE_BAR_HEIGHT - 1, x + width - 5, y + TITLE_BAR_HEIGHT, COLOR_LIGHT);

        // Small AE2-colored resize grip. It stays understated until the user needs it.
        int hx = x + width - RESIZE_HANDLE;
        int hy = y + height - RESIZE_HANDLE;
        g.fill(hx + 1, y + height - 3, x + width - 2, y + height - 2, COLOR_MUTED);
        g.fill(x + width - 3, hy + 1, x + width - 2, y + height - 2, COLOR_MUTED);
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
