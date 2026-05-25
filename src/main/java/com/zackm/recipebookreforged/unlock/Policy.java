package com.zackm.recipebookreforged.unlock;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.List;
import java.util.Set;

/**
 * The rule that decides when a recipe transitions from locked to unlocked
 * given the player's seen-items set.
 *
 * <p>Two implementations:
 * <ul>
 *   <li>{@link Any} &mdash; recipe unlocks as soon as one ingredient is seen
 *       (vanilla-style, generous).</li>
 *   <li>{@link All} &mdash; recipe unlocks only after every ingredient has been seen.
 *       The mod default.</li>
 * </ul>
 *
 * <p>Both use {@link RecipeIndex#safeGetIngredients} / {@link RecipeIndex#safeGetIngredientItems}
 * so misbehaving modded recipes can't crash the evaluation. An ingredient is "satisfied"
 * if any item it resolves to via {@code Ingredient#getItems()} is in {@code seenItems}.
 */
public sealed interface Policy permits Policy.Any, Policy.All {

    boolean isSatisfied(RecipeHolder<?> holder, Set<ResourceLocation> seenItems);

    enum Kind {
        ANY(new Any()),
        ALL(new All());

        private final Policy impl;

        Kind(Policy impl) {
            this.impl = impl;
        }

        public Policy impl() {
            return impl;
        }
    }

    // --- Implementations -----------------------------------------------------

    final class Any implements Policy {
        @Override
        public boolean isSatisfied(RecipeHolder<?> holder, Set<ResourceLocation> seenItems) {
            for (Ingredient ing : RecipeIndex.safeGetIngredients(holder)) {
                if (ing.isEmpty()) continue;
                for (ItemStack stack : RecipeIndex.safeGetIngredientItems(ing, holder)) {
                    if (stack == null || stack.isEmpty()) continue;
                    if (seenItems.contains(BuiltInRegistries.ITEM.getKey(stack.getItem()))) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    final class All implements Policy {
        @Override
        public boolean isSatisfied(RecipeHolder<?> holder, Set<ResourceLocation> seenItems) {
            List<Ingredient> ingredients = RecipeIndex.safeGetIngredients(holder);
            if (ingredients.isEmpty()) {
                // Recipe lists no ingredients (e.g. map cloning). Treat as satisfied.
                return true;
            }
            for (Ingredient ing : ingredients) {
                if (ing.isEmpty()) continue;
                boolean ingSatisfied = false;
                for (ItemStack stack : RecipeIndex.safeGetIngredientItems(ing, holder)) {
                    if (stack == null || stack.isEmpty()) continue;
                    if (seenItems.contains(BuiltInRegistries.ITEM.getKey(stack.getItem()))) {
                        ingSatisfied = true;
                        break;
                    }
                }
                if (!ingSatisfied) return false;
            }
            return true;
        }
    }
}
