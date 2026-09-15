package com.lhy.mest.client;

/**
 * AE2 {@code MEStorageScreen} search restore. The vanilla terminal keeps one static
 * {@code rememberedSearch}, writes it in {@code removed()}/{@code storeState()}, and
 * reapplies it when returning from a sub-screen or when "remember last search" is on.
 */
public final class TerminalSearchRestore {
    private TerminalSearchRestore() {}

    public static String valueToRestore(
            String rememberedSearch,
            boolean returnedFromSubScreen,
            boolean rememberLastSearch) {
        if ((returnedFromSubScreen || rememberLastSearch)
                && rememberedSearch != null && !rememberedSearch.isEmpty()) {
            return rememberedSearch;
        }
        return "";
    }
}
