package com.zackm.recipebookreforged.config;

import com.zackm.recipebookreforged.unlock.Policy;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server-side config for Recipe Book Reforged. Synced to clients automatically
 * by NeoForge for the {@code SERVER} config type, so the active unlock policy
 * is consistent across the network.
 *
 * <p>For step #4 only the unlock policy and the auto-unlock-empty-recipes flag
 * are wired. Common and client configs (see {@code DESIGN.md} §5.8) come later.
 */
public final class Config {

    private Config() {}

    public static final ModConfigSpec SERVER_SPEC;
    public static final Server SERVER;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        SERVER = new Server(builder);
        SERVER_SPEC = builder.build();
    }

    public static final class Server {
        public final ModConfigSpec.EnumValue<Policy.Kind> policy;
        public final ModConfigSpec.BooleanValue autoUnlockEmptyRecipes;

        Server(ModConfigSpec.Builder b) {
            b.push("unlock");

            policy = b
                    .comment(
                            "Unlock policy.",
                            "  ALL = recipe unlocks only after every ingredient has been seen at least once.",
                            "  ANY = recipe unlocks as soon as any one ingredient is seen (vanilla-style).")
                    .defineEnum("policy", Policy.Kind.ALL);

            autoUnlockEmptyRecipes = b
                    .comment("If true, recipes that list zero ingredients auto-unlock on login.")
                    .define("auto_unlock_empty_recipes", true);

            b.pop();
        }
    }

    /** Convenience accessor for the active unlock policy implementation. */
    public static Policy activePolicy() {
        return SERVER.policy.get().impl();
    }
}
