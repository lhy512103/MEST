package com.lhy.mest.mixin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import appeng.api.stacks.AEKey;
import appeng.client.gui.me.common.PinnedKeys;

import com.lhy.mest.client.MestPinnedKeysCap;

@Mixin(value = PinnedKeys.class, remap = false)
public abstract class PinnedKeysMixin {
    @Shadow
    @Final
    private static Map<AEKey, PinnedKeys.PinInfo> pinned;

    @Shadow
    @Final
    private static Comparator<Map.Entry<AEKey, PinnedKeys.PinInfo>> TIME_COMPARATOR;

    @Inject(method = "pinKey", at = @At("HEAD"), cancellable = true)
    private static void mest$pinToVisibleColumns(AEKey key, PinnedKeys.PinReason reason, CallbackInfo ci) {
        PinnedKeys.PinInfo info = pinned.get(key);
        if (info != null) {
            info.since = Instant.now();
        } else {
            pinned.put(key, new PinnedKeys.PinInfo(reason));
        }
        int cap = MestPinnedKeysCap.limit();
        if (pinned.size() > cap) {
            var ranked = new ArrayList<>(pinned.entrySet());
            ranked.sort(TIME_COMPARATOR);
            int overflow = pinned.size() - cap;
            for (int i = 0; i < overflow; i++) {
                pinned.remove(ranked.get(i).getKey());
            }
        }
        ci.cancel();
    }
}
