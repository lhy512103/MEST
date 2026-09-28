package com.lhy.mest.client;

import net.neoforged.fml.ModList;

import com.lhy.mest.client.dock.PanelSideBar;

/** Optional left-rail buttons for third-party upgrade cards. */
final class MestAddonUpgradeButtons {
    private Object importExport;
    private Object lightning;

    void install(MESTScreen screen, PanelSideBar sideBar) {
        if (ModList.get().isLoaded("ae2importexportcard")) {
            importExport = installImportExport(screen, sideBar);
        }
        if (ModList.get().isLoaded("ae2lt")) {
            lightning = installLightning(screen, sideBar);
        }
    }

    void update(MESTScreen screen) {
        if (importExport != null) {
            updateImportExport(screen);
        }
        if (lightning != null) {
            updateLightning(screen);
        }
    }

    private static Object installImportExport(MESTScreen screen, PanelSideBar sideBar) {
        MestImportExportUpgradeButtons buttons = new MestImportExportUpgradeButtons();
        buttons.install(screen, sideBar);
        return buttons;
    }

    private static Object installLightning(MESTScreen screen, PanelSideBar sideBar) {
        MestLightningUpgradeButtons buttons = new MestLightningUpgradeButtons();
        buttons.install(screen, sideBar);
        return buttons;
    }

    private void updateImportExport(MESTScreen screen) {
        ((MestImportExportUpgradeButtons) importExport).update(screen);
    }

    private void updateLightning(MESTScreen screen) {
        ((MestLightningUpgradeButtons) lightning).update(screen);
    }
}
