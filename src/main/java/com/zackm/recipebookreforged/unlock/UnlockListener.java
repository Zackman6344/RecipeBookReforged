package com.zackm.recipebookreforged.unlock;

import com.zackm.recipebookreforged.Attachments;
import com.zackm.recipebookreforged.RecipeBookReforgedMod;
import com.zackm.recipebookreforged.config.Config;
import com.zackm.recipebookreforged.data.PlayerUnlockData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

import java.util.Set;

/**
 * Server-side event handlers that drive the unlock pipeline.
 *
 * <p>Subscribes to pickup, craft, login, server-start, and datapack-sync events. On each
 * relevant event, mutates the player's {@link PlayerUnlockData} and logs newly unlocked
 * recipes to the server console.
 *
 * <p>The active unlock rule is supplied by {@link Config#activePolicy()} &mdash;
 * see {@link Policy} for the two implementations (ANY / ALL).
 */
@EventBusSubscriber(modid = RecipeBookReforgedMod.MODID)
public final class UnlockListener {

    private UnlockListener() {}

    // --- Index lifecycle -----------------------------------------------------

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        RecipeIndex.get().rebuild(event.getServer().getRecipeManager());
    }

    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        // Two firings: per-player on login, and once with player == null after /reload.
        // We only need to rebuild on the /reload variant; the login variant is handled
        // by onPlayerLoggedIn below.
        if (event.getPlayer() == null) {
            RecipeIndex.get().rebuild(event.getPlayerList().getServer().getRecipeManager());
        }
    }

    // --- Unlock triggers -----------------------------------------------------

    @SubscribeEvent
    public static void onItemPickup(ItemEntityPickupEvent.Post event) {
        // ItemEntityPickupEvent is fired logical-server-side when an ItemEntity is picked up.
        // Its Player can be a ServerPlayer; we filter to that since unlock data is server-owned.
        if (event.getPlayer() instanceof ServerPlayer sp) {
            processItem(sp, event.getOriginalStack().getItem());
        }
    }

    @SubscribeEvent
    public static void onItemCrafted(PlayerEvent.ItemCraftedEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            processItem(sp, event.getCrafting().getItem());
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        // Scan the player's inventory so anything they're already carrying counts.
        Inventory inv = sp.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                processItem(sp, stack.getItem());
            }
        }
    }

    // --- Core ----------------------------------------------------------------

    /**
     * Mark an item as seen for this player; then re-evaluate every recipe in the
     * reverse index that uses this item, unlocking each one that the active
     * {@link Policy} now considers satisfied.
     *
     * <p>Public so the commands package can call this after a reset to repopulate
     * unlock state from the player's current inventory.
     */
    public static void processItem(ServerPlayer player, Item item) {
        if (item == Items.AIR) return;

        PlayerUnlockData data = player.getData(Attachments.PLAYER_UNLOCK_DATA.get());
        if (!data.markSeen(item)) return; // already seen, nothing new to evaluate

        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(item);
        String playerName = player.getName().getString();
        RecipeBookReforgedMod.LOGGER.debug("[{}] seen item: {}", playerName, itemId);

        Policy policy = Config.activePolicy();
        Set<ResourceLocation> seenItems = data.seenItems();

        int newlyUnlocked = 0;
        for (RecipeHolder<?> h : RecipeIndex.get().recipesForItem(item)) {
            if (data.isUnlocked(h.id())) continue;
            if (policy.isSatisfied(h, seenItems) && data.unlock(h.id())) {
                RecipeBookReforgedMod.LOGGER.info(
                        "[{}] unlocked recipe: {}", playerName, h.id());
                newlyUnlocked++;
            }
        }
        if (newlyUnlocked > 0) {
            RecipeBookReforgedMod.LOGGER.debug(
                    "[{}] {} newly unlocked from {}", playerName, newlyUnlocked, itemId);
        }
    }
}
