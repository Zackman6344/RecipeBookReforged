package com.zackm.recipebookreforged.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.zackm.recipebookreforged.Attachments;
import com.zackm.recipebookreforged.RecipeBookReforgedMod;
import com.zackm.recipebookreforged.config.Config;
import com.zackm.recipebookreforged.data.PlayerUnlockData;
import com.zackm.recipebookreforged.unlock.RecipeIndex;
import com.zackm.recipebookreforged.unlock.UnlockListener;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.List;

/**
 * Server-side commands. Registered under {@code /recipebookreforged} with an
 * {@code /rbr} alias.
 *
 * <p>For the in-game testing milestone, two subcommands are wired:
 * <ul>
 *   <li>{@code dump} &mdash; shows the executor's unlock counts and a sample of recipe IDs.</li>
 *   <li>{@code reset} &mdash; clears the executor's seen-items and unlocked-recipes sets,
 *       then re-scans their current inventory so currently-held items immediately re-populate
 *       the seen set.</li>
 * </ul>
 *
 * <p>Each takes an optional {@code <player>} argument gated on permission level 2 (op).
 */
@EventBusSubscriber(modid = RecipeBookReforgedMod.MODID)
public final class ModCommands {

    private static final int SAMPLE_SIZE = 10;

    private ModCommands() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("recipebookreforged")
                .then(Commands.literal("dump")
                        .executes(ModCommands::dumpSelf)
                        .then(Commands.argument("player", EntityArgument.player())
                                .requires(src -> src.hasPermission(2))
                                .executes(ModCommands::dumpTarget)))
                .then(Commands.literal("reset")
                        .executes(ModCommands::resetSelf)
                        .then(Commands.argument("player", EntityArgument.player())
                                .requires(src -> src.hasPermission(2))
                                .executes(ModCommands::resetTarget)));

        LiteralCommandNode<CommandSourceStack> registered = event.getDispatcher().register(root);
        event.getDispatcher().register(Commands.literal("rbr").redirect(registered));
    }

    // --- dump ----------------------------------------------------------------

    private static int dumpSelf(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer self = ctx.getSource().getPlayer();
        if (self == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "Recipe Book Reforged: this command must be run by a player (or pass a target)."));
            return 0;
        }
        return doDump(ctx.getSource(), self);
    }

    private static int dumpTarget(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return doDump(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"));
    }

    private static int doDump(CommandSourceStack src, ServerPlayer target) {
        PlayerUnlockData data = target.getData(Attachments.PLAYER_UNLOCK_DATA.get());
        int unlocked = data.unlockedRecipes().size();
        int seen = data.seenItems().size();
        int totalIndexed = RecipeIndex.get().totalIndexedRecipes();
        String policyName = Config.SERVER.policy.get().name();
        String playerName = target.getName().getString();

        src.sendSuccess(() -> Component.literal("Recipe Book Reforged ")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal("[" + policyName + " policy]").withStyle(ChatFormatting.GRAY)), false);

        src.sendSuccess(() -> Component.literal(String.format(
                "  %s: %d / %d recipes unlocked, %d items seen",
                playerName, unlocked, totalIndexed, seen)), false);

        if (unlocked > 0) {
            List<String> sample = data.unlockedRecipes().stream()
                    .sorted()
                    .limit(SAMPLE_SIZE)
                    .map(ResourceLocation::toString)
                    .toList();
            String more = unlocked > SAMPLE_SIZE ? "  (+" + (unlocked - SAMPLE_SIZE) + " more)" : "";
            src.sendSuccess(() -> Component.literal(
                    "  First " + sample.size() + ": " + String.join(", ", sample) + more
            ).withStyle(ChatFormatting.GRAY), false);
        }

        return unlocked;
    }

    // --- reset ---------------------------------------------------------------

    private static int resetSelf(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer self = ctx.getSource().getPlayer();
        if (self == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "Recipe Book Reforged: this command must be run by a player (or pass a target)."));
            return 0;
        }
        return doReset(ctx.getSource(), self);
    }

    private static int resetTarget(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return doReset(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"));
    }

    private static int doReset(CommandSourceStack src, ServerPlayer target) {
        PlayerUnlockData data = target.getData(Attachments.PLAYER_UNLOCK_DATA.get());
        int before = data.unlockedRecipes().size();
        data.clear();

        // Re-populate from current inventory so the player isn't completely empty after reset.
        Inventory inv = target.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                UnlockListener.processItem(target, stack.getItem());
            }
        }

        int after = data.unlockedRecipes().size();
        String playerName = target.getName().getString();
        src.sendSuccess(() -> Component.literal(
                "Recipe Book Reforged: reset " + playerName + " (was " + before + " unlocks, now " + after + " after inventory re-scan)"
        ).withStyle(ChatFormatting.YELLOW), true);
        return after;
    }
}
