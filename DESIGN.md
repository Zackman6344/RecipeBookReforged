# Recipe Book Reforged — Design Document

**Target:** Minecraft 1.21.1, NeoForge
**Mod ID:** `recipebookreforged`
**Java root package:** `com.zackm.recipebookreforged`
**Status:** Draft v0.4 — fully specced

---

## 1. Goals

A standalone recipe book that:

1. Behaves *in feel* like the vanilla recipe book — players progressively discover recipes as they obtain ingredients.
2. Works in heavily-modded packs where the vanilla book is half-empty because mod authors didn't ship `recipes/*` unlock advancements.
3. Surfaces **every** registered recipe type — vanilla and modded — through one consistent UI.
4. Does not fight with JEI / EMI / REI by default. Optional, opt-in filtering hooks for users who want full progression-gated viewers.
5. Is robust against unusual `Recipe` implementations from other mods (custom ingredient shapes, fluid inputs, etc.) — degrades gracefully rather than crashing.

## 2. Non-goals (v1)

- **One-click "fill grid from book"** — vanilla's book can stage ingredients into the crafting grid; we will not replicate this in v1. Pure browsing/discovery only.
- **Replacing JEI/EMI/REI.** This is a *progression* book, not a recipe viewer.
- **Custom rendering of every modded recipe type's UI.** We render generically from `getIngredients()` + `getResultItem(RegistryAccess)`. Mod-specific rich displays are out of scope.
- **Shared / team unlock state** across multiple players.
- **Persistent search history, favorites, achievements.** (Future versions, maybe.)
- **Pre-1.21.1 or Forge support.** NeoForge 1.21.1 only.

## 3. Glossary

| Term | Meaning |
|---|---|
| **Seen item** | An item ID the player has ever picked up or produced as a craft result. Tracked in a server-side set. |
| **Unlocked recipe** | A recipe whose unlock condition is met based on the player's seen-items set. Derived state, cached. |
| **Unlock policy** | The rule that decides when a recipe transitions from locked → unlocked. ANY (any ingredient seen) or ALL (every ingredient seen). |
| **Reverse index** | Cached map of `Item → List<RecipeHolder>` so a single pickup event triggers O(few) recipe re-evaluations. |
| **Recipe type** | `RecipeType<?>` registered in `BuiltInRegistries.RECIPE_TYPE`. Vanilla has 9 (crafting, smelting, blasting, smoking, campfire, stonecutting, smithing_transform, smithing_trim, plus a few legacy). Modded packs commonly add 5–30 more. |
| **Soft-dep** | Mod dependency that is optional at runtime. We guard JEI/EMI/REI integrations behind `ModList.get().isLoaded(...)`. |

## 4. High-level architecture

```
                     ┌──────────────────────────────────────────────┐
                     │                  SERVER                       │
                     │                                                │
  Pickup / craft ──► │  UnlockListener  ─► PlayerUnlockData (attached │
  events             │       │              to Player via Attachment) │
                     │       │                                        │
                     │       ▼                                        │
                     │  evaluatePolicy(player, recipe)                │
                     │       │                                        │
                     │       ▼ (on new unlock)                        │
                     │  Send S2CUnlockDelta ──┐                       │
                     │                         │                      │
                     │  RecipeIndex (rebuilt on RecipesUpdatedEvent)  │
                     │                                                │
                     └─────────────────────────┼──────────────────────┘
                                               │
                                               ▼
                     ┌──────────────────────────────────────────────┐
                     │                  CLIENT                       │
                     │                                                │
                     │  ClientUnlockCache  ◄── packets                │
                     │       │                                        │
                     │       ▼                                        │
                     │  RecipeBookScreen ── reads cache + RecipeMgr   │
                     │                                                │
                     │  (optional) JEI/EMI/REI plugins ── consult cache│
                     └────────────────────────────────────────────────┘
```

## 5. Component design

### 5.1 PlayerUnlockData (`data/PlayerUnlockData.java`)

Per-player state, attached to `Player` via `AttachmentType`.

```java
public final class PlayerUnlockData {
    private final Set<ResourceLocation> seenItems = new HashSet<>();
    private final Set<ResourceLocation> unlockedRecipes = new HashSet<>();

    public boolean markSeen(Item item);              // returns true if newly added
    public boolean unlock(ResourceLocation recipeId); // returns true if newly unlocked
    public boolean isUnlocked(ResourceLocation id);
    public Set<ResourceLocation> seenItems();
    public Set<ResourceLocation> unlockedRecipes();
}
```

Serialization via a `Codec<PlayerUnlockData>`:

```java
public static final Codec<PlayerUnlockData> CODEC = RecordCodecBuilder.create(i -> i.group(
    ResourceLocation.CODEC.listOf().fieldOf("seen_items").forGetter(...),
    ResourceLocation.CODEC.listOf().fieldOf("unlocked").forGetter(...)
).apply(i, PlayerUnlockData::fromLists));
```

Registered as an `AttachmentType`:

```java
public static final Supplier<AttachmentType<PlayerUnlockData>> UNLOCK_DATA =
    ATTACHMENT_TYPES.register("unlock_data",
        () -> AttachmentType.builder(PlayerUnlockData::new)
            .serialize(PlayerUnlockData.CODEC)
            .copyOnDeath()       // persist across respawn
            .build());
```

Convenience accessor:

```java
PlayerUnlockData data = player.getData(Attachments.UNLOCK_DATA.get());
```

### 5.2 RecipeIndex (`unlock/RecipeIndex.java`)

Server-side cache built once on `RecipesUpdatedEvent` (also rebuilt on `/reload`):

```java
public final class RecipeIndex {
    private final Map<ResourceLocation /*item*/, List<RecipeHolder<?>>> byIngredient;
    private final Map<RecipeType<?>, List<RecipeHolder<?>>> byType;

    public void rebuild(RecipeManager mgr, RegistryAccess access) {
        byIngredient.clear();
        for (RecipeHolder<?> h : mgr.getRecipes()) {
            for (Ingredient ing : safeGetIngredients(h.value())) {
                for (ItemStack stack : ing.getItems()) {
                    byIngredient.computeIfAbsent(
                        BuiltInRegistries.ITEM.getKey(stack.getItem()),
                        k -> new ArrayList<>()
                    ).add(h);
                }
            }
        }
    }

    private static List<Ingredient> safeGetIngredients(Recipe<?> r) {
        try {
            return r.getIngredients();   // may throw or return null in misbehaving mods
        } catch (Throwable t) {
            return List.of();
        }
    }
}
```

**Why a reverse index:** without it, every item pickup would scan all recipes (~thousands in big packs). With it, pickup → small list of candidates → cheap policy check.

### 5.3 UnlockListener (`unlock/UnlockListener.java`)

Server-side `@SubscribeEvent` handlers on `NeoForge.EVENT_BUS`:

| Event | Action |
|---|---|
| `PlayerEvent.ItemPickupEvent` | call `processItem(player, stack.getItem())` |
| `ItemEntityPickupEvent.Post` | same |
| `PlayerEvent.ItemCraftedEvent` | call `processItem(player, craftedStack.getItem())` |
| `PlayerEvent.PlayerLoggedInEvent` | scan current inventory + send full sync |
| `PlayerEvent.PlayerChangeGameModeEvent` | re-send full sync (creative bypass on/off) |
| `RecipesUpdatedEvent` | `RecipeIndex.rebuild(...)` then re-evaluate all players online |
| `OnDatapackSyncEvent` | server-side fire after datapack reload to refresh client state |

`processItem` pseudo-code:

```java
void processItem(ServerPlayer player, Item item) {
    PlayerUnlockData data = player.getData(Attachments.UNLOCK_DATA);
    if (!data.markSeen(item)) return;            // already known
    List<ResourceLocation> newlyUnlocked = new ArrayList<>();
    for (RecipeHolder<?> h : index.recipesForItem(item)) {
        if (data.isUnlocked(h.id())) continue;
        if (Policy.current().isSatisfied(h.value(), data.seenItems())) {
            data.unlock(h.id());
            newlyUnlocked.add(h.id());
        }
    }
    if (!newlyUnlocked.isEmpty()) {
        Network.sendUnlockDelta(player, newlyUnlocked);
    }
}
```

### 5.4 Unlock policy (`unlock/Policy.java`)

```java
public sealed interface Policy permits Any, All {
    boolean isSatisfied(Recipe<?> recipe, Set<ResourceLocation> seenItems);

    final class Any implements Policy { /* recipe.getIngredients().stream().anyMatch(...) */ }
    final class All implements Policy { /* recipe.getIngredients().stream().allMatch(...) */ }
}
```

A single `Ingredient` is "satisfied" if any item it resolves to (via `ingredient.getItems()`) is in `seenItems`. Empty/null ingredients are treated as satisfied (matches vanilla's "no input" recipes like map cloning).

Selected via server config (see §7), defaulting to `All`.

**Edge case — recipes with empty ingredient list:** auto-unlock on player login.

**Edge case — recipe whose `getIngredients()` throws or returns null:** treat as never auto-unlockable; still listed in UI but with a "manual unlock only" badge.

**Creative-mode bypass:** when `creative_bypass = true` (default), players in creative mode are treated by both the server and client as if every recipe is unlocked. Server still tracks `seenItems` normally so switching to survival is seamless. On gamemode change, server re-sends a full sync (creative→survival reveals the player's "real" unlock set, survival→creative reveals all).

### 5.5 Networking (`network/Payloads.java`)

NeoForge 1.21.1 payload system. Two S2C payloads, both carrying the full unlock + seenItems data shape (full sync) or the deltas (incremental):

```java
public record S2CFullSync(
    Set<ResourceLocation> unlocked,
    Set<ResourceLocation> seenItems
) implements CustomPacketPayload { ... }

public record S2CDelta(
    List<ResourceLocation> newlyUnlocked,
    List<ResourceLocation> newlySeen
) implements CustomPacketPayload { ... }
```

**Why sync `seenItems`:** the client needs it to render *progress hints* on locked recipes — e.g. "3/5 ingredients seen" in the detail pane, partial silhouette opacity proportional to coverage, and search-on-progress filters. Server is still authoritative for unlock state; the client receives `seenItems` purely as display data and never makes unlock decisions from it.

Registered in `RegisterPayloadHandlersEvent`:

```java
@SubscribeEvent
public static void onRegister(RegisterPayloadHandlersEvent event) {
    PayloadRegistrar reg = event.registrar("1");
    reg.playToClient(S2CFullSync.TYPE, S2CFullSync.CODEC, ClientNet::onFullSync);
    reg.playToClient(S2CDelta.TYPE,    S2CDelta.CODEC,    ClientNet::onDelta);
}
```

No C2S needed for state — commands go through vanilla command dispatch (see §5.9).

### 5.6 Client-side state (`client/ClientUnlockCache.java`)

Singleton holding the latest known unlock + seen-items state. Mutated by packet handlers, queried by the screen, sort comparators, and (optionally) viewer integrations.

```java
public final class ClientUnlockCache {
    private static final Set<ResourceLocation> unlocked  = ConcurrentHashMap.newKeySet();
    private static final Set<ResourceLocation> seenItems = ConcurrentHashMap.newKeySet();

    public static boolean isUnlocked(ResourceLocation id);
    public static boolean hasSeen(ResourceLocation itemId);
    public static int seenIngredientCount(Recipe<?> r);   // for "3/5 seen" hints
    public static Set<ResourceLocation> unlockedSnapshot();
    public static Set<ResourceLocation> seenItemsSnapshot();
    static void replaceAll(Set<RL> unlocked, Set<RL> seen);
    static void applyDelta(Collection<RL> unlocks, Collection<RL> seen);
}
```

**Craftable-now check** is computed on demand from `Minecraft.getInstance().player.getInventory()` — see §5.7.7 for the heuristic used.

### 5.7 UI

#### 5.7.1 Entry points

Two ways to open the book:

1. **Global keybind.** `KeyMapping` registered via `RegisterKeyMappingsEvent`, default unbound (so it doesn't fight other mods' bindings). User assigns in Controls. Suggested default if free: `R`.
2. **In-GUI button.** When a `CraftingScreen` or `InventoryScreen` opens, we inject a small button beside the vanilla recipe-book toggle that opens our book overlaid on the current screen. See §5.7.5.

#### 5.7.2 Tab grouping — two-axis with flip

Recipes are grouped on two axes: **source mod** and **recipe type**. The user can pivot which axis is primary (left strip) via a toggle in the header.

**Mod-first mode** (default):
- Left strip: one row per source mod (vanilla pinned to top, modded below an `─` divider, alphabetical within each group).
- Top strip: filter chips for recipe types present in the selected mod (e.g. clicking "fabric" shows chips for `crafting`, `smelting`, `stonecutting`, etc. — only types that mod contributes).
- All-types is the default secondary filter.

**Type-first mode**:
- Left strip: one row per `RecipeType<?>` that has at least one recipe.
- Top strip: filter chips for source mods that contribute recipes of the selected type.
- All-mods is the default secondary filter.

Toggle is a button in the header `[Group: Mod ▾]` / `[Group: Type ▾]`. State persists in the client config.

Data model stays a flat `List<RecipeHolder<?>>` with a `(mod_id, type_id)` derived key per holder; both views are projections over the same list. No server-side changes — pure client UI.

#### 5.7.3 Screen layout (mod-first mode shown)

```
┌─ Recipe Book Reforged ───────────── [Group: Mod ▾] [⚙] [X]┐
│ ┌────────────────────────────────────────────────────────┐ │
│ │ 🔍 [search...                       ] [☐ show locked]  │ │
│ └────────────────────────────────────────────────────────┘ │
│  Types: [All] [Crafting] [Smelting] [Stonecutting] …       │
├──────┬─────────────────────────────────────────────────────┤
│ vnla │ ┌──┬──┬──┬──┬──┬──┬──┬──┬──┐    Page 1/7  [◀][▶]   │
│ ──── │ │🟫│🟫│🟫│🟫│🟫│  │  │  │  │                        │
│ crea │ ├──┼──┼──┼──┼──┼──┼──┼──┼──┤                        │
│ mek  │ │🟫│  │  │  │  │  │  │  │  │                        │
│ blod │ └──┴──┴──┴──┴──┴──┴──┴──┴──┘                        │
│ farm │                                                      │
│ ind  │ ┌─ <selected recipe> ──────────────────────────────┐ │
│ ...  │ │ Inputs:  [a] + [b] + [c]                         │ │
│      │ │ Output:  [result x4]                             │ │
│      │ │ Type:    minecraft:crafting_shaped               │ │
│      │ │ Source:  examplemod:my_recipe                    │ │
│      │ │ Status:  ✓ Unlocked                              │ │
│      │ └──────────────────────────────────────────────────┘ │
└──────┴─────────────────────────────────────────────────────┘
```

Type-first mode swaps the meaning of the left strip and top chips — same widgets, inverted axes.

**Left strip icons:**
- Mod-first: mod icon from `ModList.get().getModContainerById(...).getModInfo().getLogoFile()`. Fallback: first letter on a colored chip.
- Type-first: known vanilla icons (crafting table, furnace, …) for vanilla types; for modded types, try an icon registered by mods via our `RecipeTypeIconRegistry` API, else fall back to the result of the first recipe under that type.

**Top chips:**
- Compact pill buttons, scrollable horizontally if they overflow.
- Active chip drawn inverted.

**Grid:** result item per cell. Locked recipes are hidden by default; toggling "show locked" reveals them as silhouettes (grayscale + 50% alpha). Hover tooltip shows recipe ID, source mod, type, and unlock status.

**Detail pane:** populated when a cell is clicked. Inputs use `Recipe#getIngredients()` and cycle through `ingredient.getItems()` if multiple (visual cycling like JEI). Output uses `Recipe#getResultItem(RegistryAccess)`. If either returns empty/null, render a "?" placeholder with the recipe ID for transparency.

#### 5.7.4 Search

Substring match against:
- Output item display name
- Output item ID
- Recipe ID
- Recipe type ID
- Source mod name

Case-insensitive. Searches across all tabs when typed; jumps to the matching tab if user presses Enter.

#### 5.7.5 In-GUI button injection (`client/CraftingScreenInjector.java`)

Adds a small button to vanilla crafting GUIs that opens the Recipe Book Reforged screen.

**Mechanism:** subscribe to `ScreenEvent.Init.Post` on the **mod event bus's client side**. Filter the event to screens we want to augment:

```java
@SubscribeEvent
public static void onScreenInit(ScreenEvent.Init.Post e) {
    Screen s = e.getScreen();
    if (s instanceof CraftingScreen cs)         injectButton(e, cs);
    else if (s instanceof InventoryScreen is)   injectButton(e, is);
}
```

`injectButton(...)` adds an `ImageButton` (or our own `RecipeBookButton`) at a position offset from the vanilla recipe-book toggle (so the two coexist cleanly). The button's `onPress` action:

```java
() -> Minecraft.getInstance().setScreen(new RecipeBookReforgedScreen(parent));
```

The book screen remembers its parent so closing it returns the player to the original crafting/inventory screen rather than the world.

**Coverage in v1:** vanilla's `CraftingScreen` (crafting table) and `InventoryScreen` (player inventory). Modded crafting GUIs not covered automatically; we expose a `RecipeBookButton.injectInto(AbstractContainerScreen<?>)` helper so mod authors can opt in. Config flag `inject_into_modded_screens = false` (default off) attempts a best-effort injection on any `AbstractContainerScreen` if the user wants to gamble.

**Coexistence with vanilla recipe book:** vanilla's toggle remains untouched. Our button is visually distinct (different icon/color) so the player can use whichever they prefer.

#### 5.7.6 Sort order & "craftable now" hint

Default sort within any tab/group is **craftable-now first, then alphabetical by output name**.

**Craftable-now heuristic (client-side):**

```java
boolean isCraftableNow(Recipe<?> recipe, Inventory inv) {
    // Approximate: every ingredient slot has at least one matching item in the inventory.
    // Not shape-aware, doesn't account for shared ingredients consuming the same stack.
    // Good enough as a sort hint; the player's craft attempt is still validated by vanilla.
    for (Ingredient ing : safeGetIngredients(recipe)) {
        if (ing.isEmpty()) continue;
        boolean anyMatch = false;
        for (ItemStack stack : ing.getItems()) {
            if (inv.contains(stack)) { anyMatch = true; break; }
        }
        if (!anyMatch) return false;
    }
    return true;
}
```

**Caveats and how we communicate them:**
- Not shape-aware (a shaped recipe needing 4 corner planks won't notice if you only have 3).
- Doesn't track shared-stack consumption (1 stick can't be 2 ingredients).
- Doesn't check data-component constraints (e.g. an enchanted-book ingredient).
- → Therefore we render a 🟢 dot next to recipe results that pass the heuristic, with a tooltip "Looks craftable — may still fail if shape or counts don't match." Not a promise.

**Sort comparator:**

```java
Comparator<RecipeHolder<?>> byCraftableThenName = Comparator
    .comparing((RecipeHolder<?> h) -> !isCraftableNow(h.value(), playerInv))  // craftable first
    .thenComparing(h -> displayName(h.value().getResultItem(access)).getString().toLowerCase());
```

Other sort modes (`alphabetical`, `by_mod`, `by_type`, `recent_unlocks_first`) are still available via the client config dropdown — `craftable_then_alphabetical` is just the default.

**Refresh:** sort runs once when the book opens and again on `ContainerEvents.PlayerContainerEvent` (any inventory change while open), throttled to no more than once per ~250ms.

#### 5.7.7 Logo & visual style

Aesthetic target: **deliberately amateur "MS Paint by a non-artist, but passable"** — visibly hand-drawn, slightly crooked lines, wobbly proportions, limited palette, clearly the work of someone who is not a trained pixel artist but who took enough time that it doesn't look like a placeholder. Style references: hand-traced mouse drawings, the unpolished-but-charming look of early-2000s personal websites, the deliberately rough aesthetic of mods like *Eating Animation* or the early *Quark* logo.

**Concrete style rules for the logo and any custom UI textures:**
- Outlined in solid black, 1–2px lines, not perfectly straight. Slight wobble is intentional.
- Limited palette: ~6 flat colors max, no gradients, no anti-aliasing.
- Mild perspective errors are OK — the book in the logo can be slightly skewed.
- No drop shadows, no glow, no chrome. Plain background or a single-color rectangle behind.
- Text in the logo, if present, hand-lettered in the same mouse-drawn style — not a system font.

**Files this affects:**
- `logo.png` (in mod root, shown in the Mods menu) — 256×256, transparent background, drawn in the above style.
- `assets/recipebookreforged/textures/gui/recipe_book.png` — the main screen background. Same style; visible imperfections in the borders, hand-drawn tab edges.
- `assets/recipebookreforged/textures/gui/button_book.png` — the in-GUI button icon.

These are art assets — not something I can produce, but the spec captures the brief so whoever draws them (you or someone else) has a clear target.

### 5.8 Config (`config/Config.java`)

NeoForge `ModConfigSpec`. Three configs:

#### Server config — `recipebookreforged-server.toml` (synced to clients)

```toml
[unlock]
# ALL = recipe unlocks only after every ingredient has been seen
# ANY = recipe unlocks as soon as any one ingredient is seen
policy = "ALL"

# If true, recipes with no listed ingredients auto-unlock on login.
auto_unlock_empty_recipes = true

# If true, creative-mode players see every recipe as unlocked regardless of
# what they've actually picked up. The server still tracks seenItems for them
# (so switching back to survival is seamless), but the book and any viewer
# integrations behave as if everything is unlocked.
creative_bypass = true

[scope]
# RecipeType IDs to include. Empty list = all.
include_types = []
# RecipeType IDs to exclude even if include_types is empty.
exclude_types = []
# Recipe ID regex patterns to hide entirely from the book.
hidden_recipe_patterns = []
```

#### Common config — `recipebookreforged-common.toml`

```toml
[behavior]
# Persist unlocks across player death.
copy_on_death = true
# Reset unlocks on dimension change / advancement-based progression resets (rare).
reset_on_advancement = ""
```

#### Client config — `recipebookreforged-client.toml`

```toml
[ui]
# Default tab when opening the book ("first_modded", "vanilla_crafting", "last_used").
default_tab = "last_used"
# Primary grouping axis: "mod_first" (mods left, types as chips) or
# "type_first" (types left, mods as chips). User can flip live via the
# header toggle; this is the remembered default.
group_mode = "mod_first"
# Show silhouettes of locked recipes (false = locked recipes hidden entirely).
show_locked_silhouettes = false
sort_mode = "craftable_then_alphabetical"
# Options:
#   craftable_then_alphabetical (default) — items the player can craft right now float to top
#   alphabetical
#   by_mod
#   by_type
#   recent_unlocks_first
# Attempt to inject the recipe-book button into modded crafting GUIs as well.
# Off by default — best-effort, may misalign on some screens.
inject_into_modded_screens = false

[integrations]
# When true, our JEI plugin hides locked recipes from JEI's view.
hide_locked_in_jei = false
hide_locked_in_emi = false
hide_locked_in_rei = false
```

### 5.9 Commands (`command/ModCommands.java`)

Registered on `RegisterCommandsEvent`. All under the `/recipebookreforged` root (with alias `/rbr`).

| Command | Permission | Effect |
|---|---|---|
| `/recipebookreforged reset` | 0 (any player) | Resets the **executor's own** `seenItems` and `unlockedRecipes` to empty; re-runs login scan against current inventory so currently-held items immediately re-populate. Sends a full sync. |
| `/recipebookreforged reset <player>` | 2 (op) | Same effect, targeted at another player. Server-side only command path. |
| `/recipebookreforged unlock <recipe_id>` | 2 (op) | Manually unlocks a recipe for the executor (debug / recovery). |
| `/recipebookreforged unlock <player> <recipe_id>` | 2 (op) | Same, for another player. |
| `/recipebookreforged dump` | 0 | Prints the executor's unlock count and a paginated list. Useful for testing. |
| `/recipebookreforged dump <player>` | 2 (op) | Same, for another player. |

Argument types use vanilla `EntityArgument.player()` and `ResourceLocationArgument` (with a suggestion provider that completes against currently-registered recipe IDs).

Confirmations: the no-arg `reset` prompts the executor with a "Type `/rbr reset confirm` within 15s to proceed" guard, to avoid accidental wipes. The targeted op variant skips the confirmation (admins know what they're doing).

### 5.10 Third-party viewer integration (`compat/...`)

Three optional modules, each compiled against the respective viewer's API and guarded by `ModList.get().isLoaded(...)`.

**JEI** (`@JeiPlugin` class in `compat/jei/`):
- Implement `IRecipeManagerPlugin` or use `IRecipeManager#hideRecipes(...)` on join + on every unlock delta.
- Listen to `ClientUnlockCache` changes via a simple callback we expose.
- Hidden ↔ shown toggled by the `hide_locked_in_jei` config flag.

**EMI** (`EmiPlugin` SPI):
- Use `EmiApi.setDisplayPredicate(...)` or per-display visibility via `EmiRecipeManager`.

**REI** (`REIClientPlugin`):
- Use `CategoryRegistry` / `DisplayRegistry#registerVisibilityPredicate`.

Each is a Gradle sub-source-set with optional dependency declared in `neoforge.mods.toml` (`incompatibilities = []`, `dependencies = [...]` marked `mandatory = false`). Specific API calls TBD during implementation — the contract is small enough that exact versions can be confirmed then.

## 6. Data flow scenarios

### 6.1 Fresh player joins server

1. `PlayerLoggedInEvent` fires.
2. Server scans current inventory; calls `processItem(...)` for each unique item → may unlock some recipes.
3. Server sends `S2CFullSync(unlocked)` to client.
4. Client populates `ClientUnlockCache`.
5. If JEI/EMI/REI integration enabled, plugin runs an initial hide pass.

### 6.2 Player picks up new item

1. `PlayerEvent.ItemPickupEvent` fires server-side.
2. `UnlockListener.processItem(...)` adds to `seenItems`, queries `RecipeIndex` for recipes using that item, evaluates each under the active `Policy`.
3. Newly unlocked recipe IDs collected into a delta list.
4. `S2CUnlockDelta` sent to that player only.
5. Client cache updated; if the book screen is open, it refreshes; if JEI/EMI/REI integration enabled, plugin updates filters.

### 6.3 Datapack reload (`/reload`)

1. `OnDatapackSyncEvent` fires.
2. `RecipeIndex.rebuild(...)` runs on the server.
3. For each online player, server re-evaluates all recipes against `seenItems` (some recipes may have appeared/disappeared/changed).
4. Net change (added + removed unlock IDs) sent as a delta.

### 6.4 Modded recipe with broken `getIngredients()`

1. During `RecipeIndex.rebuild`, `safeGetIngredients(r)` catches the exception, returns empty.
2. Recipe is **not** indexed for any item.
3. Recipe is still discoverable in the book under its `RecipeType` tab.
4. Recipe is marked "manual unlock only" — can never auto-unlock via play.
5. Optional `/recipebookreforged unlock <recipe>` admin command provides a workaround.

## 7. Edge cases & compatibility risks

| Risk | Mitigation |
|---|---|
| Modded recipe's `getIngredients()` throws | `safeGetIngredients` try/catch wrapper; recipe still listed, never auto-unlocks |
| Modded recipe's `getResultItem()` returns `EMPTY` | Render "?" in UI; still listable |
| Modded recipe type has thousands of recipes (e.g. all-pairs combinator) | Cap displayed per type at config'd limit (default 5000), warn in log |
| Player has full inventory, pickup event doesn't fire | `ItemEntityPickupEvent.Pre` still fires; hook it as a secondary trigger |
| Recipes that consume items in non-ingredient ways (fluids, energy) | Out of scope — we use only `getIngredients()` |
| Recipe replaced by datapack during play | `RecipesUpdatedEvent` triggers re-eval; orphaned unlocked IDs are dropped on next sync |
| AttachmentType serialization breaks across mod updates | Use additive fields; version the codec via `Codec.dispatch` if schema evolves |
| Server has the mod, client doesn't (or vice versa) | Mod is `clientside-only`-incompatible: declare `side = BOTH` in mods.toml; server still works headless but UI is client-only |
| Singleplayer / integrated server | All code paths same — `Player#getData(...)` works in both |
| Creative mode players | Same rules apply by default; config flag `creative_auto_unlock_all = false` to override |

## 8. Implementation roadmap

Suggested order, each step independently testable:

1. **Project scaffold** — Gradle (NeoGradle), `neoforge.mods.toml`, main mod class, logger, empty `@SubscribeEvent` handlers.
2. **AttachmentType + persistence** — `PlayerUnlockData` with codec; verify it survives logout/login by spawning the player with `seenItems = {"minecraft:stick"}` and reloading.
3. **RecipeIndex + UnlockListener (ANY policy)** — wire pickup event, log "unlocked X" to console. No UI yet. Validates the discovery engine.
4. **Policy switch** — add `Policy` interface, config option, ALL policy. Same chat-log validation.
5. **Creative-mode bypass** — server-side gating on game mode, gamemode-change event re-sync.
6. **Networking** — `S2CFullSync` + `S2CDelta` (both `unlocked` and `seenItems`). Client cache holds both sets.
7. **Commands** — `/recipebookreforged reset` (self), `reset <player>` (op), `unlock`, `dump`. Validates sync end-to-end and gives a recovery path before any UI exists.
8. **Recipe book screen v0** — single flat grid of unlocked recipes, no tabs, validates rendering pipeline.
9. **Mod-first grouping** — left strip = mods, top chips = types, working grid.
10. **Type-first grouping + flip toggle** — same widgets, inverted projection; persist mode in client config.
11. **Detail pane** — generic ingredient/result renderer with defensive fallbacks; "X/Y seen" progress hint on locked recipes (uses `seenItems`).
12. **Craftable-now sort** — inventory heuristic + 🟢 marker + throttled refresh on inventory change.
13. **In-GUI button injection** — `CraftingScreen` + `InventoryScreen`; parent-screen return-to.
14. **Search + locked silhouettes + remaining sort modes**.
15. **Config completion** — all three config files, hot-reloaded where possible.
16. **JEI integration** (behind soft-dep + config flag).
17. **EMI integration**.
18. **REI integration**.
19. **Art pass** — `logo.png`, GUI textures, button icon (MS-Paint amateur style — see §5.7.7).
20. **Polish pass** — translations (`en_us.json`), accessibility (keyboard nav).

## 9. Open questions

All previously-open questions are now resolved (see §11 changelog for what was decided). Items intentionally deferred to future versions live in §2 (Non-goals) and the implementation roadmap.

If new questions surface during scaffolding or coding, they'll be added back here as they're discovered.

## 10. Glossary of files to create

```
src/main/
├── java/com/zackm/recipebookreforged/
│   ├── RecipeBookReforgedMod.java               # entrypoint
│   ├── Attachments.java                         # AttachmentType registry
│   ├── data/PlayerUnlockData.java
│   ├── unlock/UnlockListener.java
│   ├── unlock/RecipeIndex.java
│   ├── unlock/Policy.java
│   ├── network/Payloads.java
│   ├── network/ClientNet.java
│   ├── command/ModCommands.java                 # /recipebookreforged subcommands
│   ├── api/RecipeTypeIconRegistry.java          # public API for mods to register icons
│   ├── client/ClientUnlockCache.java
│   ├── client/KeyBindings.java
│   ├── client/ClientEvents.java
│   ├── client/CraftingScreenInjector.java       # ScreenEvent.Init.Post handler
│   ├── client/gui/RecipeBookReforgedScreen.java
│   ├── client/gui/RecipeListWidget.java
│   ├── client/gui/RecipeDetailWidget.java
│   ├── client/gui/PrimaryTabStrip.java          # left strip (mods or types)
│   ├── client/gui/SecondaryChipBar.java         # top chips (types or mods)
│   ├── client/gui/GroupModeToggle.java          # header pivot button
│   ├── client/gui/RecipeBookButton.java         # the in-GUI button widget
│   ├── compat/jei/JeiCompat.java                # @JeiPlugin
│   ├── compat/emi/EmiCompat.java
│   ├── compat/rei/ReiCompat.java
│   └── config/Config.java
└── resources/
    ├── META-INF/neoforge.mods.toml
    ├── pack.mcmeta
    ├── logo.png                                 # MS-Paint amateur style
    └── assets/recipebookreforged/
        ├── lang/en_us.json
        └── textures/gui/
            ├── recipe_book.png                  # main screen background
            └── button_book.png                  # in-GUI button icon
```

## 11. Changelog

**v0.4** — fully specced; ready for scaffolding.
- Added §5.9 Commands (`reset`, `unlock`, `dump`; self / op variants).
- §5.5 networking: payloads now carry both `unlocked` and `seenItems` (combined full-sync and delta payload).
- §5.6 client cache: now tracks `seenItems` too for progress-hint rendering.
- Added §5.7.6 Sort order with the craftable-now heuristic, the 🟢 marker, and the inventory-change refresh trigger.
- §5.8 default `sort_mode` changed from `alphabetical` to `craftable_then_alphabetical`.
- §10 file list: added `command/ModCommands.java`.
- §9 open questions: cleared. All four resolved.

**v0.3** — UI decisions locked in.
- Added §5.7.2 Tab grouping (two-axis mod-first/type-first flip).
- Added §5.7.5 In-GUI button injection on `CraftingScreen` + `InventoryScreen`.
- Added §5.7.7 Logo & visual style (deliberate MS-Paint-amateur aesthetic).
- §5.4 Policy: creative-mode bypass added.
- §5.8 Config: `creative_bypass`, `group_mode`, `inject_into_modded_screens`.
- Roadmap expanded to 18 steps.
- File list: `CraftingScreenInjector`, `RecipeTypeIconRegistry`, `PrimaryTabStrip`, `SecondaryChipBar`, `GroupModeToggle`, `RecipeBookButton`, `logo.png`, `button_book.png`.

**v0.2** — name & ID substitution.
- Mod ID `recipebookreforged`, package `com.zackm.recipebookreforged`, main class `RecipeBookReforgedMod`.

**v0.1** — initial draft.
- Core architecture: AttachmentType for `PlayerUnlockData`, `RecipeIndex` reverse index, `UnlockListener` event hooks, networking payloads, config skeleton, JEI/EMI/REI compat plan, edge-case table, 13-step roadmap.

