package com.lhy.mest.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TerminalSearchRestoreTest {
    @Test
    void restoresWhenReturningFromASubScreenEvenIfRememberIsOff() {
        assertEquals("iron", TerminalSearchRestore.valueToRestore("iron", true, false));
    }

    @Test
    void restoresOnAFreshOpenOnlyWhenRememberIsOn() {
        assertEquals("iron", TerminalSearchRestore.valueToRestore("iron", false, true));
        assertEquals("", TerminalSearchRestore.valueToRestore("iron", false, false));
    }

    @Test
    void ignoresEmptyOrNullRememberedText() {
        assertEquals("", TerminalSearchRestore.valueToRestore("", true, true));
        assertEquals("", TerminalSearchRestore.valueToRestore(null, true, true));
    }
}
