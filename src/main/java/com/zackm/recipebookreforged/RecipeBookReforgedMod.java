package com.zackm.recipebookreforged;

import com.mojang.logging.LogUtils;
import com.zackm.recipebookreforged.config.Config;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;

@Mod(RecipeBookReforgedMod.MODID)
public final class RecipeBookReforgedMod {
    public static final String MODID = "recipebookreforged";
    public static final Logger LOGGER = LogUtils.getLogger();

    public RecipeBookReforgedMod(IEventBus modEventBus, ModContainer modContainer) {
        Attachments.register(modEventBus);
        modContainer.registerConfig(ModConfig.Type.SERVER, Config.SERVER_SPEC);
        LOGGER.info("Recipe Book Reforged v{} initialising", modContainer.getModInfo().getVersion());
    }
}
