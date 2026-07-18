package com.lhy.mest.client.dock.model;

/** A registered terminal module and its persistent user-visible state. */
public record LeafNode(String nodeId, String moduleId, boolean visible) implements LayoutNode {
    public LeafNode {
        NodeIds.requireValid(nodeId, "nodeId");
        NodeIds.requireValid(moduleId, "moduleId");
    }

    public LeafNode withVisible(boolean newVisible) {
        return visible == newVisible ? this : new LeafNode(nodeId, moduleId, newVisible);
    }
}
