package com.lhy.mest.client.dock.model;

/** A node in the recursive layout tree. A future tab node can be added without changing roots. */
public sealed interface LayoutNode permits LeafNode, SplitNode {
    String nodeId();
}
