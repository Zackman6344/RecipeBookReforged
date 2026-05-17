package com.zackm.recipebookreforged;

import com.zackm.recipebookreforged.data.PlayerUnlockData;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public final class Attachments {
    private Attachments() {}

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, RecipeBookReforgedMod.MODID);

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<PlayerUnlockData>> PLAYER_UNLOCK_DATA =
            ATTACHMENT_TYPES.register("player_unlock_data",
                    () -> AttachmentType.builder(PlayerUnlockData::new)
                            .serialize(PlayerUnlockData.CODEC)
                            .copyOnDeath()
                            .build());

    public static void register(IEventBus modEventBus) {
        ATTACHMENT_TYPES.register(modEventBus);
    }
}
