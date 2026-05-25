package com.zackm.recipebookreforged.unlock;

import com.zackm.recipebookreforged.RecipeBookReforgedMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Server-side reverse index of recipes keyed by ingredient item, plus a secondary index
 * keyed by RecipeType. Rebuilt on server start and after datapack reload.
 *
 * <p>The reverse index lets the unlock pipeline answer "which recipes care about this item?"
 * in O(few) lookups instead of scanning every recipe on each pickup. In a heavily-modded pack
 * the difference is between ~10 lookups and ~10000 per item.
 *
 * <p>All accesses to modded {@code Recipe} implementations are wrapped in defensive try/catch
 * blocks &mdash; a misbehaving third-party recipe must not be able to crash the index rebuild.
 */
public final class RecipeIndex {

    private static final RecipeIndex INSTANCE = new RecipeIndex();

    public static RecipeIndex get() {
        return INSTANCE;
    }

    private final Map<ResourceLocation, List<RecipeHolder<?>>> byIngredient = new HashMap<>();
    private final Map<RecipeType<?>, List<RecipeHolder<?>>> byType = new HashMap<>();
    private int totalRecipes = 0;

    private RecipeIndex() {}

    /**
     * Wipe and rebuild both indexes from the given {@link RecipeManager}.
     * Safe to call from the server thread on startup or after {@code /reload}.
     */
    public void rebuild(RecipeManager mgr) {
        byIngredient.clear();
        byType.clear();
        totalRecipes = 0;

        for (RecipeHolder<?> h : mgr.getRecipes()) {
            totalRecipes++;
            byType.computeIfAbsent(h.value().getType(), k -> new ArrayList<>()).add(h);

            // Dedupe per-recipe so an item appearing in multiple ingredient slots doesn't
            // produce multiple entries pointing at the same recipe.
            Set<ResourceLocation> uniqueIngredientItems = new HashSet<>();
            for (Ingredient ing : safeGetIngredients(h)) {
                if (ing.isEmpty()) continue;
                for (ItemStack stack : safeGetIngredientItems(ing, h)) {
                    if (stack == null || stack.isEmpty()) continue;
                    uniqueIngredientItems.add(BuiltInRegistries.ITEM.getKey(stack.getItem()));
                }
            }
            for (ResourceLocation itemKey : uniqueIngredientItems) {
                byIngredient.computeIfAbsent(itemKey, k -> new ArrayList<>()).add(h);
            }
        }

        RecipeBookReforgedMod.LOGGER.info(
                "RecipeIndex rebuilt: {} recipes across {} types, {} ingredient keys",
                totalRecipes, byType.size(), byIngredient.size());
    }

    /** Recipes that list this item as one of their ingredients. Empty list if none / unknown. */
    public List<RecipeHolder<?>> recipesForItem(Item item) {
        return byIngredient.getOrDefault(
                BuiltInRegistries.ITEM.getKey(item), Collections.emptyList());
    }

    /** All recipes of a given type. Empty list if the type has no registered recipes. */
    public List<RecipeHolder<?>> recipesForType(RecipeType<?> type) {
        return byType.getOrDefault(type, Collections.emptyList());
    }

    /** Read-only view of the type &rarr; recipes map. */
    public Map<RecipeType<?>, List<RecipeHolder<?>>> byType() {
        return Collections.unmodifiableMap(byType);
    }

    public int totalIndexedRecipes() {
        return totalRecipes;
    }

    // --- Defensive wrappers --------------------------------------------------

    private static List<Ingredient> safeGetIngredients(RecipeHolder<?> h) {
        try {
            Recipe<?> r = h.value();
            List<Ingredient> list = r.getIngredients();
            return list == null ? Collections.emptyList() : list;
        } catch (Throwable t) {
            RecipeBookReforgedMod.LOGGER.warn(
                    "Recipe {} threw on getIngredients(); excluding from reverse index",
                    h.id(), t);
            return Collections.emptyList();
        }
    }

    private static ItemStack[] safeGetIngredientItems(Ingredient ing, RecipeHolder<?> h) {
        try {
            ItemStack[] stacks = ing.getItems();
            return stacks == null ? new ItemStack[0] : stacks;
        } catch (Throwable t) {
            RecipeBookReforgedMod.LOGGER.warn(
                    "Recipe {} ingredient threw on getItems(); skipping that ingredient",
                    h.id(), t);
            return new ItemStack[0];
        }
    }
}
