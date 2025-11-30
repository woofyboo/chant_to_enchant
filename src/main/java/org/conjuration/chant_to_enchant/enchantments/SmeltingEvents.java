package org.conjuration.chant_to_enchant.enchantments;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import org.conjuration.chant_to_enchant.Chant_to_enchant;
import org.conjuration.chant_to_enchant.enchantments.ModEnchantments;

import java.util.List;
import java.util.Optional;

@EventBusSubscriber(modid = Chant_to_enchant.MODID)
public class SmeltingEvents {

    @SubscribeEvent
    public static void onBlockDrops(BlockDropsEvent event) {
        // уровень должен быть серверным
        ServerLevel level = event.getLevel();

        // кто ломал блок
        Entity breaker = event.getBreaker();
        if (!(breaker instanceof Player player)) {
            return;
        }

        ItemStack tool = event.getTool();
        if (tool == null || tool.isEmpty()) {
            return;
        }

        // Достаём Holder<Enchantment> из реестра по нашему ResourceKey
        HolderLookup.RegistryLookup<Enchantment> enchRegistry =
                level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        Holder<Enchantment> smeltingHolder = enchRegistry.getOrThrow(ModEnchantments.SMELTING);

        int smeltingLevel = EnchantmentHelper.getItemEnchantmentLevel(smeltingHolder, tool);
        if (smeltingLevel <= 0) {
            return;
        }

        List<ItemEntity> drops = event.getDrops();
        if (drops.isEmpty()) {
            return;
        }

        boolean anySmelted = false;

        for (ItemEntity dropEntity : drops) {
            ItemStack original = dropEntity.getItem();
            if (original.isEmpty()) {
                continue;
            }

            ItemStack smelted = getSmeltedResult(level, original);
            if (!smelted.isEmpty()) {
                dropEntity.setItem(smelted);
                anySmelted = true;
            }
        }

        // если хоть что-то переплавили — тратим +2 прочности
        if (anySmelted && tool.isDamageableItem()) {
            EquipmentSlot slot =
                    player.getMainHandItem() == tool ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND;
            tool.hurtAndBreak(2, player, slot);
        }
    }

    /**
     * Возвращает результат переплавки стака, либо ItemStack.EMPTY, если рецепта нет.
     * Умножает результат на количество во входном стака.
     */
    private static ItemStack getSmeltedResult(ServerLevel level, ItemStack input) {
        if (input.isEmpty()) {
            return ItemStack.EMPTY;
        }

        RecipeManager recipes = level.getServer().getRecipeManager();
        SingleRecipeInput recipeInput = new SingleRecipeInput(input);

        // Явно говорим, что хотим SmeltingRecipe
        Optional<RecipeHolder<SmeltingRecipe>> optional =
                recipes.getRecipeFor(RecipeType.SMELTING, recipeInput, level);

        if (optional.isEmpty()) {
            return ItemStack.EMPTY;
        }

        ItemStack result = optional.get().value().assemble(recipeInput, level.registryAccess());
        if (result.isEmpty()) {
            return ItemStack.EMPTY;
        }

        // домножаем на количество во входном стака
        result.setCount(result.getCount() * input.getCount());
        return result;
    }

    // utility-класс
    private SmeltingEvents() {
    }
}
