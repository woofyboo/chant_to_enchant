package org.conjuration.chant_to_enchant.enchantments;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import org.conjuration.chant_to_enchant.Chant_to_enchant;

import java.util.ArrayList;
import java.util.List;

public class HexlashEvents {

    private static final ResourceLocation HEXLASH_ID =
            ResourceLocation.fromNamespaceAndPath(Chant_to_enchant.MODID, "hexlash");
    private static final ResourceKey<Enchantment> HEXLASH_KEY =
            ResourceKey.create(Registries.ENCHANTMENT, HEXLASH_ID);

    private static Holder<Enchantment> HEXLASH_HOLDER = null;

    /**
     * Подписывать так:
     * NeoForge.EVENT_BUS.addListener(HexlashEvents::onDamagePre);
     */
    public static void onDamagePre(LivingDamageEvent.Pre event) {
        LivingEntity victim = event.getEntity();
        if (victim == null) return;

        var source = event.getSource();
        if (source == null) return;

        if (!(source.getEntity() instanceof LivingEntity attacker)) return;

        Level level = attacker.level();
        if (level.isClientSide) return;

        int enchLevel = getHexlashOn(attacker);
        if (enchLevel <= 0) return;

        float dmg = event.getNewDamage();
        if (dmg <= 0.0f) return;

        // Текущее состояние дебаффов
        boolean hasPoison   = victim.hasEffect(MobEffects.POISON);
        boolean hasSlow     = victim.hasEffect(MobEffects.MOVEMENT_SLOWDOWN);
        boolean hasWeakness = victim.hasEffect(MobEffects.WEAKNESS);
        boolean hasNausea   = victim.hasEffect(MobEffects.CONFUSION);
        boolean hasHunger   = victim.hasEffect(MobEffects.HUNGER);

        boolean allHexed = hasPoison && hasSlow && hasWeakness && hasNausea && hasHunger;

        // --- КЕЙС 1: все 5 эффектов уже висят -> снимаем и x4 урон ---
        if (allHexed) {
            victim.removeEffect(MobEffects.POISON);
            victim.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
            victim.removeEffect(MobEffects.WEAKNESS);
            victim.removeEffect(MobEffects.CONFUSION);
            victim.removeEffect(MobEffects.HUNGER);

            event.setNewDamage(dmg * 4.0f);
            return;
        }

        // --- КЕЙС 2: шанс навесить новый эффект ---
        float chance = getChanceForLevel(enchLevel);
        if (attacker.getRandom().nextFloat() > chance) {
            return; // не прокнуло
        }

        // Тут уже правильно: Holder<MobEffect>
        List<Holder<MobEffect>> candidates = new ArrayList<>();
        if (!hasPoison)   candidates.add(MobEffects.POISON);
        if (!hasSlow)     candidates.add(MobEffects.MOVEMENT_SLOWDOWN);
        if (!hasWeakness) candidates.add(MobEffects.WEAKNESS);
        if (!hasNausea)   candidates.add(MobEffects.CONFUSION);
        if (!hasHunger)   candidates.add(MobEffects.HUNGER);

        if (candidates.isEmpty()) {
            return;
        }

        Holder<MobEffect> chosen = candidates.get(attacker.getRandom().nextInt(candidates.size()));
        // 12 секунд = 12 * 20 тиков
        victim.addEffect(new MobEffectInstance(chosen, 12 * 20, 0));
    }

    // Шанс по уровням
    private static float getChanceForLevel(int level) {
        return switch (level) {
            case 1 -> 0.25f; // 25%
            case 2 -> 0.33f; // ~33%
            case 3 -> 0.50f; // 50%
            case 4 -> 0.66f; // ~66%
            default -> 0.0f;
        };
    }

    // Достаём holder зачара из реестра
    private static Holder<Enchantment> getHexlashHolder(Level level) {
        if (HEXLASH_HOLDER != null) return HEXLASH_HOLDER;

        Registry<Enchantment> reg =
                level.registryAccess().registryOrThrow(Registries.ENCHANTMENT);

        HEXLASH_HOLDER = reg.getHolder(HEXLASH_KEY).orElse(null);
        return HEXLASH_HOLDER;
    }

    private static int getHexlashLevel(Level level, ItemStack stack) {
        if (stack.isEmpty()) return 0;
        Holder<Enchantment> holder = getHexlashHolder(level);
        if (holder == null) return 0;
        return EnchantmentHelper.getItemEnchantmentLevel(holder, stack);
    }

    /**
     * Максимальный уровень Hexlash на руках (main/offhand).
     */
    private static int getHexlashOn(LivingEntity attacker) {
        Level level = attacker.level();
        ItemStack main = attacker.getMainHandItem();
        ItemStack off  = attacker.getOffhandItem();

        int lMain = getHexlashLevel(level, main);
        int lOff  = getHexlashLevel(level, off);
        return Math.max(lMain, lOff);
    }
}
