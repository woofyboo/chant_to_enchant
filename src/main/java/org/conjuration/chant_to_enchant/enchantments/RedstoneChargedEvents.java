package org.conjuration.chant_to_enchant.enchantments;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.core.particles.DustParticleOptions;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import org.conjuration.chant_to_enchant.Chant_to_enchant;

@EventBusSubscriber(modid = Chant_to_enchant.MODID)
public class RedstoneChargedEvents {

    // ResourceKey для нашего зачарования: chant_to_enchant:redstone_charged
    private static final ResourceKey<Enchantment> REDSTONE_CHARGED_KEY =
            ResourceKey.create(
                    Registries.ENCHANTMENT,
                    ResourceLocation.fromNamespaceAndPath(Chant_to_enchant.MODID, "redstone_charged")
            );

    // Кэшируем Holder
    private static Holder<Enchantment> REDSTONE_CHARGED_ENCHANT;

    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        LivingEntity target = event.getEntity();
        DamageSource source = event.getSource();
        Entity direct = source.getEntity();

        // Нас интересует только урон от живого атакующего (игрок/моб)
        if (!(direct instanceof LivingEntity attacker)) {
            return;
        }

        // Берём оружие из мейн-хэнда атакующего
        ItemStack weapon = attacker.getMainHandItem();
        if (weapon.isEmpty()) {
            return;
        }

        Level level = attacker.level();
        Holder<Enchantment> enchantmentHolder = getRedstoneChargedEnchant(level);
        if (enchantmentHolder == null) {
            return;
        }

        // Уровень зачарования на оружии
        int enchantLevel = EnchantmentHelper.getTagEnchantmentLevel(enchantmentHolder, weapon);
        if (enchantLevel <= 0) {
            return;
        }

        float baseDamage = event.getAmount();
        if (baseDamage <= 0.0F) {
            return;
        }

        float finalDamage = baseDamage;
        boolean usedRedstone = false;

        // 1) Пытаемся потратить редстоун и дать бонус к БАЗОВОМУ урону
        if (attacker instanceof Player player && consumeRedstoneDust(player)) {
            usedRedstone = true;

            float multiplier;
            switch (enchantLevel) {
                case 1 -> multiplier = 1.2F;
                case 2 -> multiplier = 1.3F;
                case 3 -> multiplier = 1.4F;
                default -> multiplier = 1.5F; // 4+
            }

            finalDamage = (float) Math.ceil(baseDamage * multiplier);

            // Партиклы вокруг цели, если редстоун потратили именно на неё
            if (level instanceof ServerLevel serverLevel) {
                spawnRedstoneParticles(serverLevel, target);
            }
        }

        // 2) Если редстоун НЕ потратили — вешаем дебафф
        if (!usedRedstone) {
            float reductionFactor = 1.0F - 0.2F * enchantLevel;
            if (reductionFactor < 0.0F) {
                reductionFactor = 0.0F;
            }

            float reducedDamage = (float) Math.floor(baseDamage * reductionFactor);
            if (reducedDamage < 1.0F) {
                reducedDamage = 1.0F;
            }

            finalDamage = reducedDamage;
        }

        // Записываем итоговый урон обратно в ивент
        event.setAmount(finalDamage);
    }

    /**
     * Получаем Holder нашего зачарования из реестра (лениво, один раз).
     */
    private static Holder<Enchantment> getRedstoneChargedEnchant(Level level) {
        if (REDSTONE_CHARGED_ENCHANT == null && level instanceof ServerLevel serverLevel) {
            Registry<Enchantment> registry =
                    serverLevel.registryAccess().registryOrThrow(Registries.ENCHANTMENT);
            REDSTONE_CHARGED_ENCHANT =
                    registry.getHolder(REDSTONE_CHARGED_KEY).orElse(null);
        }
        return REDSTONE_CHARGED_ENCHANT;
    }

    /**
     * Пытается потратить 1 редстоун-пыль из инвентаря игрока.
     * Возвращает true, если удалось.
     */
    private static boolean consumeRedstoneDust(Player player) {
        Inventory inv = player.getInventory();

        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.is(Items.REDSTONE)) {
                stack.shrink(1);
                if (stack.isEmpty()) {
                    inv.setItem(i, ItemStack.EMPTY);
                }
                return true;
            }
        }

        return false;
    }

    /**
     * Спавнит "редстоун" частицы вокруг цели.
     * Вызывается один раз на цель, на которую был потрачен редстоун.
     */
    private static void spawnRedstoneParticles(ServerLevel level, LivingEntity target) {
        double x = target.getX();
        double y = target.getY(0.5D);
        double z = target.getZ();

        level.sendParticles(
                DustParticleOptions.REDSTONE,
                x, y, z,
                20,      // количество частиц
                0.5D,    // разброс по X
                0.5D,    // разброс по Y
                0.5D,    // разброс по Z
                0.0D     // скорость
        );
    }
}
