package com.lhy.mest;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

import com.lhy.mest.client.MestClient;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = MESplicedterminal.MODID, dist = Dist.CLIENT)
public class MESplicedterminalClient {
    public MESplicedterminalClient(IEventBus modEventBus) {
        MestClient.init(modEventBus);
    }
}
