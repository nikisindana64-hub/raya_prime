package com.mamarala.rayaprime.scanner;

import com.mamarala.rayaprime.RayaPrimeMod;
import com.mamarala.rayaprime.config.RayaConfig;
import com.mamarala.rayaprime.entity.RayaPrimeEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Знания Раи о мире и установленных модах («База данных П.Е.Р.С.И.К.»).
 * — Индексирует реестры блоков/предметов/мобов ВСЕХ модов (после загрузки тегов, на старте сервера).
 * — Строит компактный каталог модовых ID, который уходит Gemini в системной инструкции.
 * — Ищет объекты и рецепты по словам запроса.
 * — Описывает окружение: на что смотрит игрок, блоки, мобы, биом, время, инвентари.
 */
public class ModKnowledgeScanner {

    public record Entry(String registryId, String modId, String displayName, String kind, String tags) {}

    private static final Object LOCK = new Object();
    private static final List<String> MOD_SUMMARIES = new ArrayList<>();
    private static final List<Entry> INDEX = new ArrayList<>();
    private static String modCatalog = "";
    private static boolean scanned = false;

    private static final Set<String> STOPWORDS = Set.of(
            "что", "как", "для", "это", "эту", "этот", "где", "все", "всё", "мне", "дай", "сделай", "найди", "рая", "райя", "прайм", "raya", "the", "and", "for", "или", "при", "про", "там", "тут");

    public static void scanAllInstalledMods() {
        synchronized (LOCK) {
            MOD_SUMMARIES.clear();
            INDEX.clear();
            Map<String, String> modNames = new LinkedHashMap<>();

            for (IModInfo mod : ModList.get().getMods()) {
                String modId = mod.getModId();
                if ("minecraft".equals(modId) || "neoforge".equals(modId)) continue;
                MOD_SUMMARIES.add(mod.getDisplayName() + " [" + modId + " v" + mod.getVersion() + "]");
                modNames.put(modId, mod.getDisplayName() + " v" + mod.getVersion());
            }

            for (Block block : BuiltInRegistries.BLOCK) {
                try {
                    ResourceLocation key = BuiltInRegistries.BLOCK.getKey(block);
                    String tags = block.builtInRegistryHolder().tags().limit(4)
                            .map(t -> "#" + t.location()).collect(Collectors.joining(", "));
                    INDEX.add(new Entry(key.toString(), key.getNamespace(), block.getName().getString(), "block", tags));
                } catch (Throwable ignored) {
                }
            }
            for (Item item : BuiltInRegistries.ITEM) {
                try {
                    ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
                    String tags = item.builtInRegistryHolder().tags().limit(4)
                            .map(t -> "#" + t.location()).collect(Collectors.joining(", "));
                    INDEX.add(new Entry(key.toString(), key.getNamespace(), new ItemStack(item).getHoverName().getString(), "item", tags));
                } catch (Throwable ignored) {
                }
            }
            for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
                try {
                    ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
                    INDEX.add(new Entry(key.toString(), key.getNamespace(), type.getDescription().getString(), "entity", ""));
                } catch (Throwable ignored) {
                }
            }
            modCatalog = buildCatalog(modNames);
            scanned = true;
            RayaPrimeMod.LOGGER.info("[RayaPrime] Проиндексировано {} объектов из {} модов.", INDEX.size(), modNames.size());
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            MOD_SUMMARIES.clear();
            INDEX.clear();
            modCatalog = "";
            scanned = false;
        }
    }

    private static void ensureScanned() {
        if (!scanned) {
            scanAllInstalledMods();
        }
    }

    /** Каталог ID модовых блоков/предметов/мобов: «мод: blocks[…] items[…] mobs[…]» — попадает в системную инструкцию. */
    private static String buildCatalog(Map<String, String> modNames) {
        int total = RayaConfig.MOD_CATALOG_MAX_ENTRIES.get();
        if (total <= 0 || modNames.isEmpty()) return "";

        Map<String, Set<String>> blocksByMod = new TreeMap<>();
        Map<String, Set<String>> itemsByMod = new TreeMap<>();
        Map<String, Set<String>> mobsByMod = new TreeMap<>();
        Set<String> blockIds = new LinkedHashSet<>();
        for (Entry e : INDEX) {
            if (e.kind().equals("block")) blockIds.add(e.registryId());
        }
        for (Entry e : INDEX) {
            if (!modNames.containsKey(e.modId())) continue;
            String path = e.registryId().substring(e.registryId().indexOf(':') + 1);
            switch (e.kind()) {
                case "block" -> blocksByMod.computeIfAbsent(e.modId(), k -> new LinkedHashSet<>()).add(path);
                case "item" -> {
                    if (!blockIds.contains(e.registryId())) { // предметы-блоки уже в списке блоков
                        itemsByMod.computeIfAbsent(e.modId(), k -> new LinkedHashSet<>()).add(path);
                    }
                }
                case "entity" -> mobsByMod.computeIfAbsent(e.modId(), k -> new LinkedHashSet<>()).add(path);
                default -> { }
            }
        }
        int perMod = Math.max(40, total / Math.max(1, modNames.size()));
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (Map.Entry<String, String> mod : modNames.entrySet()) {
            if (used >= total) break;
            String id = mod.getKey();
            int budget = Math.min(perMod, total - used);
            sb.append("• ").append(mod.getValue()).append(" (").append(id).append("): ");
            int[] left = {budget};
            appendGroup(sb, "блоки", blocksByMod.get(id), left);
            appendGroup(sb, "предметы", itemsByMod.get(id), left);
            appendGroup(sb, "мобы", mobsByMod.get(id), left);
            sb.append('\n');
            used += budget - left[0];
        }
        return sb.toString();
    }

    private static void appendGroup(StringBuilder sb, String label, Set<String> ids, int[] left) {
        if (ids == null || ids.isEmpty() || left[0] <= 0) return;
        List<String> shown = new ArrayList<>();
        for (String s : ids) {
            if (left[0] <= 0) break;
            shown.add(s);
            left[0]--;
        }
        sb.append(label).append('[').append(String.join(",", shown));
        if (shown.size() < ids.size()) sb.append(",…+").append(ids.size() - shown.size());
        sb.append("] ");
    }

    public static String getModCatalog() {
        ensureScanned();
        return modCatalog;
    }

    public static String getCompactModListSummary() {
        ensureScanned();
        return MOD_SUMMARIES.isEmpty() ? "Только Minecraft + NeoForge (дополнительных модов нет)" : String.join(", ", MOD_SUMMARIES);
    }

    /** Поиск объектов и рецептов по словам запроса (с оценкой релевантности). */
    public static String searchModKnowledgeForQuery(ServerLevel level, String userQuery) {
        ensureScanned();
        if (userQuery == null || userQuery.isBlank()) return "";
        String[] raw = userQuery.toLowerCase(Locale.ROOT).split("[^a-zа-яё0-9_]+");
        List<String> tokens = new ArrayList<>();
        for (String t : raw) {
            if (t.length() >= 3 && !STOPWORDS.contains(t)) tokens.add(t);
        }
        if (tokens.isEmpty()) return "";

        record Scored(Entry entry, int score) {}
        List<Scored> hits = new ArrayList<>();
        synchronized (LOCK) {
            for (Entry e : INDEX) {
                String hay = (e.registryId() + " " + e.displayName()).toLowerCase(Locale.ROOT);
                int score = 0;
                for (String t : tokens) {
                    if (hay.contains(t)) score++;
                }
                if (score > 0) {
                    if (!e.modId().equals("minecraft")) score += 1; // моды важнее ванили
                    hits.add(new Scored(e, score));
                }
            }
        }
        hits.sort((a, b) -> Integer.compare(b.score(), a.score()));

        List<String> matched = new ArrayList<>();
        Set<String> matchedIds = new LinkedHashSet<>();
        for (Scored s : hits) {
            if (matched.size() >= 15) break;
            Entry e = s.entry();
            matched.add(String.format("%s (%s, мод: %s, тип: %s%s)", e.displayName(), e.registryId(), e.modId(), e.kind(),
                    e.tags().isEmpty() ? "" : ", теги: " + e.tags()));
            matchedIds.add(e.registryId());
        }

        List<String> recipes = new ArrayList<>();
        if (!matchedIds.isEmpty()) {
            try {
                for (RecipeHolder<?> holder : level.getRecipeManager().getRecipes()) {
                    ItemStack result = holder.value().getResultItem(level.registryAccess());
                    if (result.isEmpty()) continue;
                    String resId = BuiltInRegistries.ITEM.getKey(result.getItem()).toString();
                    if (!matchedIds.contains(resId)) continue;
                    String ingredients = holder.value().getIngredients().stream()
                            .filter(ing -> !ing.isEmpty() && ing.getItems().length > 0)
                            .map(ing -> {
                                ItemStack first = ing.getItems()[0];
                                return first.getHoverName().getString() + " (" + BuiltInRegistries.ITEM.getKey(first.getItem()) + ")";
                            })
                            .distinct()
                            .collect(Collectors.joining(" + "));
                    if (!ingredients.isEmpty()) {
                        recipes.add(result.getHoverName().getString() + " [" + resId + "] <= " + ingredients);
                    }
                    if (recipes.size() >= 8) break;
                }
            } catch (Throwable t) {
                RayaPrimeMod.LOGGER.debug("[RayaPrime] Не удалось прочитать рецепты", t);
            }
        }

        StringBuilder sb = new StringBuilder();
        if (!matched.isEmpty()) {
            sb.append("Объекты из реестров, подходящие под запрос: ").append(String.join("; ", matched)).append('\n');
        }
        if (!recipes.isEmpty()) {
            sb.append("Рецепты крафта: ").append(String.join(" | ", recipes)).append('\n');
        }
        return sb.toString();
    }

    /** Полная картина окружения игрока и Раи — уходит Gemini вместе с каждым сообщением. */
    public static String buildWorldAndModContext(ServerPlayer player, RayaPrimeEntity raya, String userPrompt) {
        ServerLevel level = player.serverLevel();
        StringBuilder ctx = new StringBuilder();

        ctx.append("=== ТЕЛЕМЕТРИЯ П.Е.Р.С.И.К. ===\n");
        BlockPos ppos = player.blockPosition();
        String biome = level.getBiome(ppos).unwrapKey().map(k -> k.location().toString()).orElse("?");
        ctx.append(String.format("Мир: %s | биом: %s | время суток: %d/24000 | дождь: %s | гроза: %s\n",
                level.dimension().location(), biome, level.getDayTime() % 24000L,
                level.isRaining() ? "да" : "нет", level.isThundering() ? "да" : "нет"));
        ctx.append(String.format("Носитель: %s | HP %.1f/%.1f | голод %d/20 | координаты [%d, %d, %d]\n",
                player.getName().getString(), player.getHealth(), player.getMaxHealth(),
                player.getFoodData().getFoodLevel(), player.getBlockX(), player.getBlockY(), player.getBlockZ()));

        List<String> hotbar = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            ItemStack s = player.getInventory().items.get(i);
            if (!s.isEmpty()) hotbar.add(describeStack(s));
        }
        ItemStack off = player.getOffhandItem();
        if (!off.isEmpty()) hotbar.add("левая рука: " + describeStack(off));
        ctx.append("Быстрая панель игрока: ").append(hotbar.isEmpty() ? "пусто" : String.join(", ", hotbar)).append('\n');
        ItemStack mainHand = player.getMainHandItem();
        if (!mainHand.isEmpty()) {
            ctx.append("В руке игрока: ").append(describeStack(mainHand)).append('\n');
        }

        BlockHitResult lookHit = level.clip(new ClipContext(
                player.getEyePosition(),
                player.getEyePosition().add(player.getLookAngle().scale(8.0D)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (lookHit.getType() == HitResult.Type.BLOCK) {
            BlockPos p = lookHit.getBlockPos();
            BlockState st = level.getBlockState(p);
            ResourceLocation bid = BuiltInRegistries.BLOCK.getKey(st.getBlock());
            ctx.append(String.format("Игрок смотрит на блок: %s (%s) в [%d, %d, %d]\n",
                    st.getBlock().getName().getString(), bid, p.getX(), p.getY(), p.getZ()));
        }

        ctx.append(String.format("Тело Раи: HP %.1f/%.1f | режим %s | задач в очереди %d | координаты [%d, %d, %d]\n",
                raya.getHealth(), raya.getMaxHealth(), raya.getActiveMode(), raya.getQueuedTaskCount(),
                raya.getBlockX(), raya.getBlockY(), raya.getBlockZ()));

        SimpleContainer inv = raya.getRayaInventory();
        List<String> invItems = new ArrayList<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty()) invItems.add(describeStack(s));
        }
        ctx.append("Инвентарь Раи (27 слотов): ").append(invItems.isEmpty() ? "пусто" : String.join(", ", invItems)).append('\n');

        // Заметные/модовые блоки вокруг: берём ближайший экземпляр каждого вида
        Map<String, BlockPos> nearest = new HashMap<>();
        Map<String, Double> nearestDist = new HashMap<>();
        for (int dx = -8; dx <= 8; dx++) {
            for (int dy = -4; dy <= 5; dy++) {
                for (int dz = -8; dz <= 8; dz++) {
                    BlockPos pos = ppos.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) continue;
                    ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                    String id = key.toString();
                    boolean modded = !"minecraft".equals(key.getNamespace());
                    boolean notable = id.contains("ore") || id.contains("chest") || id.contains("spawner")
                            || id.contains("furnace") || id.contains("table") || id.contains("anvil")
                            || id.contains("barrel") || id.contains("bed") || id.contains("portal");
                    if (modded || notable) {
                        double d = pos.distSqr(ppos);
                        Double old = nearestDist.get(id);
                        if (old == null || d < old) {
                            nearestDist.put(id, d);
                            nearest.put(id, pos.immutable());
                        }
                    }
                }
            }
        }
        if (!nearest.isEmpty()) {
            String s = nearest.entrySet().stream()
                    .sorted(Comparator.comparingDouble(e -> nearestDist.get(e.getKey())))
                    .limit(20)
                    .map(e -> {
                        Block b = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(e.getKey()));
                        BlockPos p = e.getValue();
                        return b.getName().getString() + " (" + e.getKey() + ") @ [" + p.getX() + "," + p.getY() + "," + p.getZ() + "]";
                    })
                    .collect(Collectors.joining("; "));
            ctx.append("Заметные и модовые блоки рядом: ").append(s).append('\n');
        }

        AABB box = player.getBoundingBox().inflate(18.0D);
        List<Entity> nearby = level.getEntities(player, box, e -> e != raya && e.isAlive());
        if (!nearby.isEmpty()) {
            String s = nearby.stream()
                    .sorted(Comparator.comparingDouble(player::distanceToSqr))
                    .limit(14)
                    .map(e -> e.getName().getString() + " (" + BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()) + ")"
                            + (e instanceof Enemy ? " [враждебный]" : "")
                            + " @ [" + e.getBlockX() + "," + e.getBlockY() + "," + e.getBlockZ() + "]")
                    .collect(Collectors.joining("; "));
            ctx.append("Существа рядом: ").append(s).append('\n');
        }

        if (userPrompt != null && !userPrompt.isBlank()) {
            ctx.append(searchModKnowledgeForQuery(level, userPrompt));
        }
        return ctx.toString();
    }

    private static String describeStack(ItemStack s) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(s.getItem());
        return s.getHoverName().getString() + " x" + s.getCount() + " (" + id + ")";
    }
}
