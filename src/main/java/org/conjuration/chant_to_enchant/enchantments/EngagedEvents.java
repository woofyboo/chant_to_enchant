package org.conjuration.chant_to_enchant.enchantments;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

// data components (новая система хранения на ItemStack)
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;

import static org.conjuration.chant_to_enchant.Chant_to_enchant.MODID;

/**
 * Engaged I–V — для копательных инструментов.
 *
 * Механика:
 *  - Базово режет скорость копания на 10% за уровень.
 *  - Если текущий блок совпадает с «последним вскопанным этим инструментом»,
 *    применяется бонус +20% за уровень на стак, до 8 стаков.
 *  - Стак накапливается при фактическом ломании такого же блока (BreakEvent).
 *  - Если сломан другой блок — стаки сбрасываются в 0 и «последний блок» меняется.
 *
 * Хранение состояния — в DataComponents.CUSTOM_DATA (NBT внутри CustomData).
 */
public class EngagedEvents {

    // Ключ зачарования
    private static final ResourceLocation ENGAGED_ID =
            ResourceLocation.fromNamespaceAndPath(MODID, "engaged");
    private static final ResourceKey<Enchantment> ENGAGED_KEY =
            ResourceKey.create(Registries.ENCHANTMENT, ENGAGED_ID);

    // Баланс
    private static final int    MAX_LEVEL        = 5;
    private static final int    MAX_STACKS       = 8;
    private static final float  PENALTY_PER_LVL  = 0.10f; // -10%/lvl
    private static final float  BONUS_PER_LVL    = 0.20f; // +20%/lvl per stack

    // Ключи внутри CustomData
    private static final String NBT_LAST_BLOCK   = "engaged:last_block";
    private static final String NBT_STACKS       = "engaged:stacks";

    /** Модификация скорости копания. */
    public static void onBreakSpeed(PlayerEvent.BreakSpeed event) {
        Player player = event.getEntity();
        if (player == null) return;

        ItemStack tool = player.getMainHandItem();
        if (tool.isEmpty()) return;

        int level = getEngagedLevel(tool, player);
        if (level <= 0) return;

        float base = event.getNewSpeed();
        if (base <= 0f) return;

        // Базовый штраф
        float penaltyMul = 1.0f - PENALTY_PER_LVL * clamp(level, 0, MAX_LEVEL);
        if (penaltyMul < 0.0f) penaltyMul = 0.0f;

        // Совпадение блоков?
        BlockState state = event.getState();
        String curId = blockId(state);
        String lastId = getLastBlock(tool);

        int stacks = 0;
        if (lastId != null && lastId.equals(curId)) {
            stacks = clamp(getStacks(tool), 0, MAX_STACKS);
        }

        float bonusMul = 1.0f + BONUS_PER_LVL * level * stacks;
        event.setNewSpeed(base * penaltyMul * bonusMul);
    }

    /** Обновление истории/стаков при фактическом ломании блока. */
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;

        ItemStack tool = player.getMainHandItem();
        if (tool.isEmpty()) return;

        int level = getEngagedLevel(tool, player);
        if (level <= 0) return;

        String curId = blockId(event.getState());
        String lastId = getLastBlock(tool);

        if (lastId != null && lastId.equals(curId)) {
            setStacks(tool, clamp(getStacks(tool) + 1, 0, MAX_STACKS));
        } else {
            setStacks(tool, 0);
        }
        setLastBlock(tool, curId);
    }

    // ---------- ВСПОМОГАТЕЛЬНЫЕ ----------

    private static int getEngagedLevel(ItemStack stack, Player anyPlayerForRegistry) {
        Registry<Enchantment> reg = anyPlayerForRegistry.level().registryAccess().registryOrThrow(Registries.ENCHANTMENT);
        Holder<Enchantment> holder = reg.getHolder(ENGAGED_KEY).orElse(null);
        if (holder == null) return 0;
        return stack.getEnchantmentLevel(holder);
    }

    private static String blockId(BlockState state) {
        return state.getBlock().builtInRegistryHolder().key().location().toString();
    }

    // ---- Работа с CustomData на ItemStack ----

    private static String getLastBlock(ItemStack tool) {
        CustomData data = tool.get(DataComponents.CUSTOM_DATA);
        if (data == null || data.isEmpty()) return null;
        CompoundTag tag = data.copyTag();
        return tag.contains(NBT_LAST_BLOCK) ? tag.getString(NBT_LAST_BLOCK) : null;
    }

    private static void setLastBlock(ItemStack tool, String id) {
        tool.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, cd ->
                cd.update(tag -> tag.putString(NBT_LAST_BLOCK, id))
        );
    }

    private static int getStacks(ItemStack tool) {
        CustomData data = tool.get(DataComponents.CUSTOM_DATA);
        if (data == null || data.isEmpty()) return 0;
        CompoundTag tag = data.copyTag();
        return tag.contains(NBT_STACKS) ? tag.getInt(NBT_STACKS) : 0;
    }

    private static void setStacks(ItemStack tool, int stacks) {
        final int v = clamp(stacks, 0, MAX_STACKS);
        tool.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, cd ->
                cd.update(tag -> tag.putInt(NBT_STACKS, v))
        );
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
