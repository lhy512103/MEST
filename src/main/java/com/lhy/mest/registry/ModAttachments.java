package com.lhy.mest.registry;

import java.util.function.Supplier;

import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import com.lhy.mest.MESplicedterminal;
import com.lhy.mest.terminal.ToolkitInternalInventory;

public final class ModAttachments {
    private ModAttachments() {
    }

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, MESplicedterminal.MODID);

    /** Toolkit contents and extra-bar settings; owned by the player so they survive losing the terminal. */
    public static final Supplier<AttachmentType<ToolkitInternalInventory>> TOOLKIT = ATTACHMENT_TYPES.register(
            "toolkit", () -> AttachmentType.builder(ToolkitInternalInventory::new)
                    .serialize(ToolkitInternalInventory.SERIALIZER)
                    .copyOnDeath()
                    .build());
}
