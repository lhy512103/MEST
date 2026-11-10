package com.lhy.mest.client.panel;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.lhy.mest.client.panel.PanelLifecycleController.Participant;

class PanelLifecycleControllerTest {
    private final PanelLifecycleController controller = new PanelLifecycleController();

    private static class Panel implements Participant {
        final List<String> events = new ArrayList<>();
        Runnable open = () -> {};
        Runnable close = () -> {};
        Runnable tick = () -> {};
        Runnable visibility = () -> {};
        boolean consume = true;

        @Override public void onPanelOpened() { events.add("open"); open.run(); }
        @Override public void onPanelClosed() { events.add("close"); close.run(); }
        @Override public void onPanelVisibilityChanged(boolean visible) {
            events.add("visible:" + visible);
            visibility.run();
        }
        @Override public void onClientTick() { events.add("tick"); tick.run(); }
        @Override public boolean onPanelKeyPressed(int key, int scan, int modifiers) {
            events.add("press:" + key + ":" + scan + ":" + modifiers);
            return consume;
        }
        @Override public boolean onPanelKeyReleased(int key, int scan, int modifiers) {
            events.add("release:" + key + ":" + scan + ":" + modifiers);
            return consume;
        }
        @Override public boolean onPanelCharTyped(char character, int modifiers) {
            events.add("char:" + character + ":" + modifiers);
            return consume;
        }
    }

    @Test void defaultsAreNoOpAndDoNotConsumeInput() {
        Participant panel = new Participant() {};
        controller.attach(panel, true);
        controller.focusFromMouse(panel);
        assertFalse(controller.keyPressed(1, 2, 3));
        assertFalse(controller.keyReleased(1, 2, 3));
        assertFalse(controller.charTyped('a', 0));
        controller.clientTick();
        controller.close();
    }

    @Test void repeatAttachDoesNotReopenAndOnlyChangedVisibilityNotifies() {
        Panel panel = new Panel();
        controller.attach(panel, true);
        controller.attach(panel, true);
        controller.setVisible(panel, true);
        controller.attach(panel, false);
        controller.setVisible(panel, false);
        controller.setVisible(panel, true);
        assertEquals(List.of("open", "visible:true", "visible:false", "visible:true"), panel.events);
    }

    @Test void reentrantVisibilityChangeDuringOpenReportsInitialStateOnlyOnce() {
        Panel panel = new Panel();
        panel.open = () -> controller.attach(panel, false);
        controller.attach(panel, true);
        assertEquals(List.of("open", "visible:false"), panel.events);
    }

    @Test void initiallyHiddenPanelGetsItsInitialVisibility() {
        Panel panel = new Panel();
        controller.attach(panel, false);
        assertEquals(List.of("open", "visible:false"), panel.events);
    }

    @Test void usesIdentityEvenForEqualPanels() {
        class EqualPanel extends Panel {
            @Override public boolean equals(Object other) { return other instanceof EqualPanel; }
            @Override public int hashCode() { return 0; }
        }
        Panel first = new EqualPanel();
        Panel second = new EqualPanel();
        controller.attach(first, true);
        controller.attach(second, true);
        controller.detach(first);
        controller.clientTick();
        assertEquals(List.of("open", "visible:true", "close"), first.events);
        assertEquals(List.of("open", "visible:true", "tick"), second.events);
        controller.close();
        assertEquals("close", second.events.getLast());
    }

    @Test void detachAndCloseOnlyReleaseOnce() {
        Panel panel = new Panel();
        controller.attach(panel, true);
        controller.detach(panel);
        controller.detach(panel);
        controller.close();
        controller.clientTick();
        assertEquals(List.of("open", "visible:true", "close"), panel.events);
    }

    @Test void closingThenReattachingStartsNewLifetimeWithoutFocus() {
        Panel panel = new Panel();
        controller.attach(panel, true);
        controller.focusFromMouse(panel);
        controller.close();
        controller.close();
        controller.attach(panel, true);
        assertFalse(controller.keyPressed(1, 2, 3));
        controller.close();
        assertEquals(List.of("open", "visible:true", "close", "open", "visible:true", "close"), panel.events);
    }

    @Test void openAndTickNeverChooseKeyboardFocus() {
        Panel panel = new Panel();
        controller.attach(panel, true);
        controller.clientTick();
        assertFalse(controller.keyPressed(1, 2, 3));
        assertFalse(controller.keyReleased(1, 2, 3));
        assertFalse(controller.charTyped('x', 4));
        assertEquals(List.of("open", "visible:true", "tick"), panel.events);
    }

    @Test void explicitMouseFocusRoutesAllInputWithOriginalArguments() {
        Panel first = new Panel();
        Panel second = new Panel();
        controller.attach(first, true);
        controller.attach(second, true);
        controller.focusFromMouse(second);
        assertTrue(controller.keyPressed(65, 30, 2));
        assertTrue(controller.keyReleased(65, 30, 2));
        assertTrue(controller.charTyped('中', 4));
        assertEquals(List.of("open", "visible:true"), first.events);
        assertEquals(List.of("open", "visible:true", "press:65:30:2", "release:65:30:2", "char:中:4"), second.events);
    }

    @Test void nonConsumingHooksAllowHostFallback() {
        Panel panel = new Panel();
        panel.consume = false;
        controller.attach(panel, true);
        controller.focusFromMouse(panel);
        assertFalse(controller.keyPressed(1, 2, 3));
        assertFalse(controller.keyReleased(1, 2, 3));
        assertFalse(controller.charTyped('x', 0));
        assertEquals(5, panel.events.size());
    }

    @Test void hidingClearsFocusAndShowingDoesNotRestoreIt() {
        Panel panel = new Panel();
        controller.attach(panel, true);
        controller.focusFromMouse(panel);
        controller.setVisible(panel, false);
        controller.focusFromMouse(panel);
        assertFalse(controller.keyPressed(1, 2, 3));
        assertFalse(controller.keyReleased(1, 2, 3));
        assertFalse(controller.charTyped('x', 0));
        controller.setVisible(panel, true);
        assertFalse(controller.keyPressed(1, 2, 3));
    }

    @Test void outsideUnknownAndExplicitClearAllRemoveFocus() {
        Panel panel = new Panel();
        controller.attach(panel, true);
        controller.focusFromMouse(panel);
        controller.focusFromMouse(null);
        assertFalse(controller.keyPressed(1, 2, 3));
        controller.focusFromMouse(panel);
        controller.focusFromMouse(new Panel());
        assertFalse(controller.keyPressed(1, 2, 3));
        controller.focusFromMouse(panel);
        controller.clearFocus();
        assertFalse(controller.keyPressed(1, 2, 3));
    }

    @Test void detachClearsFocusEvenWhenCloseFails() {
        Panel panel = new Panel();
        panel.close = () -> { throw new IllegalStateException("close"); };
        controller.attach(panel, true);
        controller.focusFromMouse(panel);
        assertThrows(IllegalStateException.class, () -> controller.detach(panel));
        assertFalse(controller.keyPressed(1, 2, 3));
        controller.detach(panel);
        controller.close();
        assertEquals(1, panel.events.stream().filter("close"::equals).count());
    }

    @Test void hiddenAndVisiblePanelsBothReceiveClientTicks() {
        Panel shown = new Panel();
        Panel hidden = new Panel();
        controller.attach(shown, true);
        controller.attach(hidden, false);
        controller.clientTick();
        controller.clientTick();
        assertEquals(List.of("open", "visible:true", "tick", "tick"), shown.events);
        assertEquals(List.of("open", "visible:false", "tick", "tick"), hidden.events);
    }

    @Test void tickFailureDoesNotStarveOtherPanelsAndAggregatesFailures() {
        Panel first = new Panel();
        Panel second = new Panel();
        IllegalStateException failure = new IllegalStateException("first");
        AssertionError later = new AssertionError("second");
        first.tick = () -> { throw failure; };
        second.tick = () -> { throw later; };
        controller.attach(first, true);
        controller.attach(second, false);
        assertSame(failure, assertThrows(IllegalStateException.class, controller::clientTick));
        assertArrayEquals(new Throwable[] {later}, failure.getSuppressed());
        assertEquals("tick", second.events.getLast());
        controller.close();
    }

    @Test void closeReleasesAllInReverseOrderDespiteErrorsAndClearsFocusFirst() {
        List<String> released = new ArrayList<>();
        Panel first = new Panel();
        Panel second = new Panel();
        IllegalStateException firstFailure = new IllegalStateException("second closes first");
        AssertionError laterFailure = new AssertionError("first closes second");
        first.close = () -> { released.add("first"); throw laterFailure; };
        second.close = () -> {
            released.add("second");
            assertFalse(controller.keyPressed(1, 2, 3));
            controller.close();
            throw firstFailure;
        };
        controller.attach(first, true);
        controller.attach(second, true);
        controller.focusFromMouse(first);
        assertSame(firstFailure, assertThrows(IllegalStateException.class, controller::close));
        assertEquals(List.of("second", "first"), released);
        assertArrayEquals(new Throwable[] {laterFailure}, firstFailure.getSuppressed());
        controller.close();
        assertEquals(2, released.size());
    }

    @Test void failedOpenIsRolledBackAndCleanupFailureIsSuppressed() {
        Panel panel = new Panel();
        IllegalStateException failure = new IllegalStateException("open");
        IllegalArgumentException cleanup = new IllegalArgumentException("cleanup");
        panel.open = () -> { throw failure; };
        panel.close = () -> { throw cleanup; };
        assertSame(failure, assertThrows(IllegalStateException.class, () -> controller.attach(panel, true)));
        assertArrayEquals(new Throwable[] {cleanup}, failure.getSuppressed());
        controller.clientTick();
        controller.close();
        assertEquals(List.of("open", "close"), panel.events);
        panel.open = () -> {};
        panel.close = () -> {};
        controller.attach(panel, true);
        controller.close();
        assertEquals(List.of("open", "close", "open", "visible:true", "close"), panel.events);
    }

    @Test void initialVisibilityFailureAlsoRollsBack() {
        Panel panel = new Panel();
        panel.visibility = () -> { throw new IllegalStateException("visibility"); };
        assertThrows(IllegalStateException.class, () -> controller.attach(panel, false));
        controller.close();
        assertEquals(List.of("open", "visible:false", "close"), panel.events);
    }

    @Test void visibilityChangeCommitsAndClearsFocusBeforeThrowing() {
        Panel panel = new Panel();
        controller.attach(panel, true);
        controller.focusFromMouse(panel);
        panel.visibility = () -> { throw new IllegalStateException("visibility"); };
        assertThrows(IllegalStateException.class, () -> controller.setVisible(panel, false));
        controller.setVisible(panel, false);
        assertFalse(controller.keyPressed(1, 2, 3));
        controller.close();
    }

    @Test void reentrantDetachDuringOpenDoesNotNotifyClosedPanel() {
        Panel panel = new Panel();
        panel.open = () -> controller.detach(panel);
        panel.close = () -> controller.detach(panel);
        controller.attach(panel, true);
        controller.close();
        assertEquals(List.of("open", "close"), panel.events);
    }

    @Test void tickSnapshotSkipsRemovedOrReopenedRegistrationsAndDefersNewOnes() {
        Panel first = new Panel();
        Panel second = new Panel();
        Panel added = new Panel();
        first.tick = () -> {
            controller.detach(second);
            controller.attach(second, true);
            controller.attach(added, true);
        };
        controller.attach(first, true);
        controller.attach(second, true);
        controller.clientTick();
        assertFalse(second.events.contains("tick"));
        assertFalse(added.events.contains("tick"));
        first.tick = () -> {};
        controller.clientTick();
        assertEquals("tick", second.events.getLast());
        assertEquals("tick", added.events.getLast());
    }

    @Test void closeDuringTickStopsRemainingDispatch() {
        Panel first = new Panel();
        Panel second = new Panel();
        first.tick = controller::close;
        controller.attach(first, true);
        controller.attach(second, true);
        controller.clientTick();
        assertEquals(List.of("open", "visible:true", "close"), second.events);
    }

    @Test void attachDuringCloseIsRejectedWithoutLeakingAndControllerRemainsReusable() {
        Panel first = new Panel();
        Panel second = new Panel();
        first.close = () -> controller.attach(second, true);
        controller.attach(first, true);
        assertThrows(IllegalStateException.class, controller::close);
        assertTrue(second.events.isEmpty());
        controller.attach(second, true);
        controller.close();
        assertEquals(List.of("open", "visible:true", "close"), second.events);
    }

    @Test void repeatedSameExceptionDoesNotSelfSuppressOrInterruptCleanup() {
        Panel first = new Panel();
        Panel second = new Panel();
        IllegalStateException failure = new IllegalStateException("shared");
        first.close = () -> { throw failure; };
        second.close = first.close;
        controller.attach(first, true);
        controller.attach(second, true);
        assertSame(failure, assertThrows(IllegalStateException.class, controller::close));
        assertEquals("close", first.events.getLast());
        assertEquals(0, failure.getSuppressed().length);
    }

    @Test void unknownUpdatesAreIgnoredAndNullAttachIsRejected() {
        Panel panel = new Panel();
        controller.setVisible(panel, false);
        controller.detach(panel);
        assertTrue(panel.events.isEmpty());
        assertThrows(NullPointerException.class, () -> controller.attach(null, true));
    }
}
