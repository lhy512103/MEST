package com.lhy.mest.client.dock;

import java.util.ArrayDeque;
import java.util.List;

import com.lhy.mest.client.dock.model.DockWorkspace;

/**
 * Bounded ring of workspace snapshots for the one-step undo.
 *
 * <p>Snapshots are deduplicated against the top of the stack, so persistence-driven rewrites and
 * no-op replacements do not crowd out real edits. The history is purely value-based and contains
 * no rendering or input state, which keeps the gesture state machine easier to reason about.
 */
public final class WorkspaceUndoHistory {
    private final ArrayDeque<DockWorkspace> stack = new ArrayDeque<>();
    private final int limit;

    public WorkspaceUndoHistory(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        this.limit = limit;
    }

    public boolean isEmpty() {
        return stack.isEmpty();
    }

    public int size() {
        return stack.size();
    }

    /**
     * Remember {@code snapshot} for a future undo. Returns {@code true} only when the stack
     * transitioned from empty to non-empty, so callers can invalidate dependent UI state.
     */
    public boolean remember(DockWorkspace snapshot) {
        if (snapshot == null) {
            return false;
        }
        if (!stack.isEmpty() && snapshot.equals(stack.peekFirst())) {
            return false;
        }
        boolean wasEmpty = stack.isEmpty();
        stack.addFirst(snapshot);
        while (stack.size() > limit) {
            stack.removeLast();
        }
        return wasEmpty;
    }

    /**
     * Remove and return the most recent snapshot, or {@code null} if the history is empty.
     */
    public DockWorkspace pop() {
        return stack.isEmpty() ? null : stack.removeFirst();
    }

    public void clear() {
        stack.clear();
    }

    /** Copy of the current history, for {@link #restore}. */
    public List<DockWorkspace> snapshot() {
        return List.copyOf(stack);
    }

    /** Puts back a history taken with {@link #snapshot}, dropping everything remembered since. */
    public void restore(List<DockWorkspace> snapshot) {
        stack.clear();
        stack.addAll(snapshot);
    }
}
