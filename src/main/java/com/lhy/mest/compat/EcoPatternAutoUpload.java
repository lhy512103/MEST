package com.lhy.mest.compat;

import net.minecraft.world.item.ItemStack;

import appeng.api.networking.IGrid;

import cn.dancingsnow.neoecoae.api.IECOPatternStorage;
import cn.dancingsnow.neoecoae.api.IECOPatternStorageService;

final class EcoPatternAutoUpload {
    private EcoPatternAutoUpload() {
    }

    static boolean insert(IGrid grid, ItemStack pattern) {
        IECOPatternStorageService service = grid.getService(IECOPatternStorageService.class);
        if (service == null) {
            return false;
        }
        IECOPatternStorage storage = service.getPatternStorage();
        return storage != null && storage.insertPattern(pattern.copy());
    }
}
