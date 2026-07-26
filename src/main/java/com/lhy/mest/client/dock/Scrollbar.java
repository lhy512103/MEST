package com.lhy.mest.client.dock;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;

import appeng.core.AppEng;

/**
 * Shared scrollbar state + rendering for the floating panels. Holds the current scroll offset and
 * the thumb-drag state machine; geometry is supplied per call so the helper tracks a resizable panel.
 *
 * <p>The scroll model is generic "visible / total / maxScroll": the thumb covers a fraction of the
 * track proportional to {@code visible/total}, and dragging maps the thumb position back to a scroll
 * row. This fits the ME list (rows of RepoSlots), pattern access (provider rows) and the processing
 * encoder (slot rows) without per-panel drag code.
 */
public class Scrollbar {
    private static final int HANDLE_HEIGHT = 15;

    private int scroll;
    private boolean dragging;
    // Vertical offset between the mouse and the thumb's top edge at drag start, so the thumb doesn't
    // snap to the cursor.
    private double grabOffset;

    public int scroll() {
        return scroll;
    }

    public void setScroll(int value) {
        this.scroll = value;
    }

    public void clamp(int maxScroll) {
        scroll = Math.max(0, Math.min(maxScroll, scroll));
    }

    /** Fixed AE2-style thumb height, clamped to the track. */
    private static int thumbHeight(int trackH) {
        return Math.min(HANDLE_HEIGHT, trackH);
    }

    private static int thumbY(int trackY, int trackH, int thumbH, int scroll, int maxScroll) {
        if (maxScroll <= 0) {
            return trackY;
        }
        int travel = trackH - thumbH;
        return trackY + travel * scroll / maxScroll;
    }

    /** Current thumb rect, for hit-testing. */
    public Rect2i thumbRect(int trackX, int trackY, int trackW, int trackH, int maxScroll) {
        int thumbH = thumbHeight(trackH);
        int thumbY = thumbY(trackY, trackH, thumbH, scroll, maxScroll);
        return new Rect2i(trackX, thumbY, trackW, thumbH);
    }

    /** Draw the same fixed-size AE2 scroller handle used by stock terminal widgets. */
    public void render(GuiGraphics g, int trackX, int trackY, int trackW, int trackH, int maxScroll) {
        g.fill(trackX, trackY, trackX + trackW, trackY + trackH, ModulePanel.COLOR_DARK);
        g.fill(trackX + 1, trackY, trackX + trackW - 1, trackY + trackH, ModulePanel.COLOR_LIGHT);
        int thumbH = thumbHeight(trackH);
        int thumbY = thumbY(trackY, trackH, thumbH, scroll, maxScroll);
        String sprite = maxScroll <= 0 ? "small_scroller_disabled" : "small_scroller";
        g.blitSprite(AppEng.makeId(sprite), trackX, thumbY, trackW, thumbH);
    }

    /**
     * Handle a press inside the track. Returns true if consumed (thumb grab or page-jump). When
     * consumed, the caller must route subsequent {@link #mouseDragged} / {@link #mouseReleased} here.
     */
    public boolean mousePressed(double mx, double my, int trackX, int trackY, int trackW, int trackH,
            int visible, int maxScroll) {
        if (maxScroll <= 0) {
            return false;
        }
        if (!(mx >= trackX && mx < trackX + trackW && my >= trackY && my < trackY + trackH)) {
            return false;
        }
        int thumbH = thumbHeight(trackH);
        int thumbY = thumbY(trackY, trackH, thumbH, scroll, maxScroll);
        if (my >= thumbY && my < thumbY + thumbH) {
            // Grab the thumb.
            dragging = true;
            grabOffset = my - thumbY;
        } else if (my < thumbY) {
            // Page up.
            scroll = Math.max(0, scroll - visible);
        } else {
            // Page down.
            scroll = Math.min(maxScroll, scroll + visible);
        }
        return true;
    }

    /** Drag the thumb. Only meaningful after a thumb-grabbing {@link #mousePressed}. */
    public boolean mouseDragged(double my, int trackY, int trackH, int maxScroll) {
        if (!dragging || maxScroll <= 0) {
            return false;
        }
        int thumbH = thumbHeight(trackH);
        int travel = trackH - thumbH;
        if (travel <= 0) {
            return true;
        }
        double thumbTop = my - grabOffset;
        double ratio = (thumbTop - trackY) / travel;
        scroll = Math.max(0, Math.min(maxScroll, (int) Math.round(ratio * maxScroll)));
        return true;
    }

    public void mouseReleased() {
        dragging = false;
    }

    public boolean isDragging() {
        return dragging;
    }
}
