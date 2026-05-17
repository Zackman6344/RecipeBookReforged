package com.zackm.recipebookreforged.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class PlayerUnlockData {

    public static final Codec<PlayerUnlockData> CODEC = RecordCodecBuilder.create(i -> i.group(
            ResourceLocation.CODEC.listOf().fieldOf("unlocked")
                    .forGetter(d -> new ArrayList<>(d.unlockedRecipes)),
            ResourceLocation.CODEC.listOf().fieldOf("seen_items")
                    .forGetter(d -> new ArrayList<>(d.seenItems))
    ).apply(i, PlayerUnlockData::fromLists));

    private final Set<ResourceLocation> unlockedRecipes;
    private final Set<ResourceLocation> seenItems;

    public PlayerUnlockData() {
        this(new HashSet<>(), new HashSet<>());
    }

    private PlayerUnlockData(Set<ResourceLocation> unlocked, Set<ResourceLocation> seen) {
        this.unlockedRecipes = unlocked;
        this.seenItems = seen;
    }

    private static PlayerUnlockData fromLists(List<ResourceLocation> unlocked, List<ResourceLocation> seen) {
        return new PlayerUnlockData(new HashSet<>(unlocked), new HashSet<>(seen));
    }

    public boolean markSeen(Item item) {
        return seenItems.add(BuiltInRegistries.ITEM.getKey(item));
    }

    public boolean markSeen(ResourceLocation itemId) {
        return seenItems.add(itemId);
    }

    public boolean unlock(ResourceLocation recipeId) {
        return unlockedRecipes.add(recipeId);
    }

    public boolean isUnlocked(ResourceLocation id) {
        return unlockedRecipes.contains(id);
    }

    public boolean hasSeen(ResourceLocation itemId) {
        return seenItems.contains(itemId);
    }

    public Set<ResourceLocation> unlockedRecipes() {
        return Collections.unmodifiableSet(unlockedRecipes);
    }

    public Set<ResourceLocation> seenItems() {
        return Collections.unmodifiableSet(seenItems);
    }

    public void clear() {
        unlockedRecipes.clear();
        seenItems.clear();
    }
}
