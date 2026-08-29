package com.lhy.mest.client.dock;

import com.lhy.mest.client.dock.model.DockEdge;
import com.lhy.mest.client.dock.model.DockRect;

/**
 * The leaf (and its edge) that a dragged root would splice into if released. The {@code
 * highlightBounds} is the rect used to draw the drop preview, not the dragged root's own bounds.
 */
public record DropCandidate(String targetNodeId, DockEdge edge, DockRect highlightBounds) {
}
