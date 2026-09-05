package com.lhy.mest.client.dock;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

import com.lhy.mest.client.dock.model.LayoutProjection;
import com.lhy.mest.client.dock.model.LayoutTrees;
import com.lhy.mest.client.dock.model.FloatingRoot;
import com.lhy.mest.client.dock.model.LeafNode;

/**
 * Window chrome layout that cannot be derived from the projection alone: which spliced panel
 * paints the rightmost edge and how adjacent outside scroll rails merge into one background.
 */
final class DockChromeLayout {
    private DockChromeLayout() {
    }

    static void markRightmostLeaves(
            FloatingRoot root,
            LayoutProjection next,
            java.util.Map<String, ModulePanel> panelsByModuleId) {
        List<ModulePanel> visible = new ArrayList<>();
        for (LeafNode leaf : LayoutTrees.leaves(root.content())) {
            if (next.visibleLeaf(leaf.nodeId()).isEmpty()) {
                continue;
            }
            ModulePanel panel = panelsByModuleId.get(leaf.moduleId());
            if (panel != null && panel.visible) {
                visible.add(panel);
            }
        }
        for (ModulePanel panel : visible) {
            boolean rightmost = true;
            int panelRight = panel.x + panel.width;
            for (ModulePanel other : visible) {
                if (other != panel && other.x >= panelRight - 1) {
                    rightmost = false;
                    break;
                }
            }
            panel.rightmostInWindow = rightmost;
        }
    }

    static void joinOutsideRails(Iterable<ModulePanel> panels) {
        var clusters = new HashMap<String, List<ModulePanel>>();
        for (ModulePanel panel : panels) {
            panel.resetJoinedRail();
            if (!panel.visible || !panel.hasJoinableOutsideRail()) {
                continue;
            }
            int railX = panel.x + panel.width;
            String key = (panel.splicedWindow == null ? System.identityHashCode(panel) : System.identityHashCode(panel.splicedWindow))
                    + ":" + railX;
            clusters.computeIfAbsent(key, ignored -> new ArrayList<>()).add(panel);
        }
        for (List<ModulePanel> group : clusters.values()) {
            if (group.size() < 2) {
                continue;
            }
            group.sort(Comparator.comparingInt(panel -> panel.y));
            int clusterStart = 0;
            for (int index = 1; index <= group.size(); index++) {
                boolean split = index == group.size()
                        || group.get(index).y > group.get(index - 1).y + group.get(index - 1).height + 2;
                if (!split) {
                    continue;
                }
                if (index - clusterStart >= 2) {
                    ModulePanel first = group.get(clusterStart);
                    ModulePanel last = group.get(index - 1);
                    first.joinedRailY = first.y;
                    first.joinedRailH = last.y + last.height - first.joinedRailY;
                    for (int joined = clusterStart + 1; joined < index; joined++) {
                        group.get(joined).drawOutsideRail = false;
                    }
                }
                clusterStart = index;
            }
        }
    }
}
