package com.lhy.mest.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import com.lhy.mest.client.dock.PanelSideBar;
import com.ultramega.ae2importexportcard.registry.ModItems;
import com.ultramega.ae2importexportcard.screen.UpgradeItemButton;
import com.ultramega.ae2importexportcard.util.UpgradeInterface;
import com.ultramega.ae2importexportcard.util.UpgradeType;

final class MestImportExportUpgradeButtons {
    private Button importButton;
    private Button exportButton;

    void install(MESTScreen screen, PanelSideBar sideBar) {
        if (!(screen.getMenu() instanceof UpgradeInterface upgradeInterface)) {
            return;
        }
        importButton = new UpgradeItemButton(
                btn -> upgradeInterface.ae2ImportExportCard$openMenu(UpgradeType.IMPORT),
                ResourceLocation.fromNamespaceAndPath("ae2importexportcard", "textures/gui/import_card.png"));
        importButton.setMessage(Component.translatable(ModItems.IMPORT_CARD.get().getDescriptionId()));
        importButton.visible = false;
        sideBar.add(importButton);

        exportButton = new UpgradeItemButton(
                btn -> upgradeInterface.ae2ImportExportCard$openMenu(UpgradeType.EXPORT),
                ResourceLocation.fromNamespaceAndPath("ae2importexportcard", "textures/gui/export_card.png"));
        exportButton.setMessage(Component.translatable(ModItems.EXPORT_CARD.get().getDescriptionId()));
        exportButton.visible = false;
        sideBar.add(exportButton);
    }

    void update(MESTScreen screen) {
        if (importButton != null) {
            importButton.visible = screen.getHost().getInstalledUpgrades(ModItems.IMPORT_CARD.get()) > 0;
        }
        if (exportButton != null) {
            exportButton.visible = screen.getHost().getInstalledUpgrades(ModItems.EXPORT_CARD.get()) > 0;
        }
    }
}
