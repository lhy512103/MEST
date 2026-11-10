package com.lhy.mest.client.panel;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Client-thread-only lifecycle and explicit keyboard focus for extension panels; no Minecraft types.
 * The host attaches after layout, synchronizes visibility before dispatch, calls {@link #clientTick()}
 * from its client/container tick (NEVER render), and calls {@link #close()} on screen removal.
 * Reinitializing/resizing the same screen may attach the same instances again without reopening them.
 * Closing is reusable: returning from a layout editor may attach those instances for a new lifetime.
 *
 * <p>Callbacks may detach panels. Tick iteration uses registration snapshots, so newly attached or
 * reopened instances wait until the next tick. State changes commit before callbacks. Open failure
 * rolls back and attempts close; tick and close attempt every remaining callback before rethrowing
 * the first failure with later failures suppressed. No callback exception is silently swallowed.
 */
public final class PanelLifecycleController implements AutoCloseable {
    /**
     * Extension contract inherited by ModulePanel. All hooks are optional. Key hooks return true
     * only when consumed, allowing the host's normal input handling otherwise.
     */
    public interface Participant {
        /** Allocate subscriptions/resources once per open lifetime. */
        default void onPanelOpened() {}

        /** Release resources, including those allocated before a failed open. Called once per lifetime. */
        default void onPanelClosed() {}

        /** Initial visibility after open, then changes only; hiding does not close the panel. */
        default void onPanelVisibilityChanged(boolean visible) {}

        /**
         * Once per host client tick for ALL opened panels, including hidden panels. Not a frame hook.
         * Use the visibility hook to suspend background subscriptions or other expensive hidden work.
         */
        default void onClientTick() {}

        default boolean onPanelKeyPressed(int keyCode, int scanCode, int modifiers) { return false; }

        default boolean onPanelKeyReleased(int keyCode, int scanCode, int modifiers) { return false; }

        default boolean onPanelCharTyped(char character, int modifiers) { return false; }
    }

    private static final class Entry {
        private final Participant participant;
        private boolean visible;
        private boolean opened;

        private Entry(Participant participant, boolean visible) {
            this.participant = participant;
            this.visible = visible;
        }
    }

    private final IdentityHashMap<Participant, Entry> entries = new IdentityHashMap<>();
    private final List<Entry> order = new ArrayList<>();
    private Participant focused;
    private boolean closing;

    /**
     * Opens by object identity, not equals or panel id. Repeated attach only synchronizes visibility.
     * A new lifetime reports opened then initial visibility, and never implicitly takes focus.
     */
    public void attach(Participant participant, boolean visible) {
        Objects.requireNonNull(participant, "participant");
        if (closing) {
            throw new IllegalStateException("Cannot attach while closing panels");
        }
        if (entries.containsKey(participant)) {
            setVisible(participant, visible);
            return;
        }
        Entry entry = new Entry(participant, visible);
        entries.put(participant, entry);
        order.add(entry);
        try {
            participant.onPanelOpened();
            if (entries.get(participant) == entry) {
                entry.opened = true;
                participant.onPanelVisibilityChanged(entry.visible);
            }
        } catch (RuntimeException | Error failure) {
            if (entries.get(participant) == entry) {
                try {
                    detach(participant);
                } catch (RuntimeException | Error cleanupFailure) {
                    suppress(failure, cleanupFailure);
                }
            }
            throw failure;
        }
    }

    /** Updates an attached panel; unknown instances are ignored. Hiding permanently clears focus. */
    public void setVisible(Participant participant, boolean visible) {
        Entry entry = entries.get(participant);
        if (entry == null || entry.visible == visible) {
            return;
        }
        entry.visible = visible;
        if (!visible && focused == participant) {
            clearFocus();
        }
        // An open hook may reentrantly update visibility; report its final initial value once.
        if (entry.opened) {
            participant.onPanelVisibilityChanged(visible);
        }
    }

    /** Removes before invoking close, so repeated/reentrant detach cannot close twice. */
    public void detach(Participant participant) {
        Entry entry = entries.remove(participant);
        if (entry == null) {
            return;
        }
        order.remove(entry);
        if (focused == participant) {
            clearFocus();
        }
        participant.onPanelClosed();
    }

    /**
     * Called only for an explicit mouse selection, not hover, render, tab order or layout updates.
     * Pass null on clicks outside panels or into another input owner (for example the global search).
     * Unknown/hidden candidates clear rather than retain the previous focus.
     */
    public void focusFromMouse(Participant participant) {
        Entry entry = entries.get(participant);
        focused = entry != null && entry.visible ? participant : null;
    }

    public void clearFocus() {
        focused = null;
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        Participant target = inputTarget();
        return target != null && target.onPanelKeyPressed(keyCode, scanCode, modifiers);
    }

    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        Participant target = inputTarget();
        return target != null && target.onPanelKeyReleased(keyCode, scanCode, modifiers);
    }

    public boolean charTyped(char character, int modifiers) {
        Participant target = inputTarget();
        return target != null && target.onPanelCharTyped(character, modifiers);
    }

    private Participant inputTarget() {
        Entry entry = entries.get(focused);
        return entry != null && entry.visible ? focused : null;
    }

    /** Dispatch from containerTick only; does not replace any built-in panel frame/update method. */
    public void clientTick() {
        Throwable failure = null;
        for (Entry entry : List.copyOf(order)) {
            if (entries.get(entry.participant) != entry) {
                continue;
            }
            try {
                entry.participant.onClientTick();
            } catch (RuntimeException | Error next) {
                failure = combine(failure, next);
            }
        }
        rethrow(failure);
    }

    /**
     * Clears all registrations and focus before releasing in reverse attach order. Reentrant close
     * is a no-op; attach during close is rejected so cleanup cannot leak newly opened resources.
     * Even a throwing close hook cannot prevent the other panels from releasing their resources.
     */
    @Override
    public void close() {
        if (closing) {
            return;
        }
        List<Entry> closingEntries = List.copyOf(order);
        entries.clear();
        order.clear();
        clearFocus();
        closing = true;
        Throwable failure = null;
        try {
            for (int i = closingEntries.size() - 1; i >= 0; i--) {
                try {
                    closingEntries.get(i).participant.onPanelClosed();
                } catch (RuntimeException | Error next) {
                    failure = combine(failure, next);
                }
            }
        } finally {
            closing = false;
        }
        rethrow(failure);
    }

    private static Throwable combine(Throwable first, Throwable next) {
        if (first == null) {
            return next;
        }
        suppress(first, next);
        return first;
    }

    private static void suppress(Throwable first, Throwable next) {
        if (first != next) {
            first.addSuppressed(next);
        }
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }
}
