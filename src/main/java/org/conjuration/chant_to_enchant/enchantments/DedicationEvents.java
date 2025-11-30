package org.conjuration.chant_to_enchant.enchantments;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import org.conjuration.chant_to_enchant.Chant_to_enchant;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@EventBusSubscriber(modid = Chant_to_enchant.MODID)
public class DedicationEvents {

    // текущие стаки на игроке
    private static final Map<UUID, Integer> stacks = new HashMap<>();
    // текущая сфокусированная цель
    private static final Map<UUID, Integer> lastTarget = new HashMap<>();

    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        DamageSource source = event.getSource();
        if (!(source.getEntity() instanceof Player player)) return;

        ItemStack weapon = player.getMainHandItem();
        if (weapon.isEmpty()) return;

        Registry<Enchantment> enchantmentRegistry =
                player.level().registryAccess().registryOrThrow(Registries.ENCHANTMENT);

        Holder<Enchantment> dedicationHolder =
                enchantmentRegistry.getHolderOrThrow(ModEnchantments.DEDICATION);

        int level = EnchantmentHelper.getItemEnchantmentLevel(dedicationHolder, weapon);
        if (level <= 0) return;

        // работаем только на полностью заряженных ударах
        float attackStrength = player.getAttackStrengthScale(0.5f);
        if (attackStrength < 0.99f) {
            return;
        }

        LivingEntity victim = event.getEntity();
        UUID playerId = player.getUUID();
        int victimId = victim.getId();

        // ---------- ЛОГИКА СТАКОВ И ЦЕЛИ ----------

        int currentStacks = stacks.getOrDefault(playerId, 0);
        Integer lastVictimId = lastTarget.get(playerId);

        // смена цели — сброс стаков
        if (lastVictimId == null || lastVictimId != victimId) {
            currentStacks = 0;
            lastTarget.put(playerId, victimId);
        }

        // стеки после текущего удара (1..5)
        int nextStacks = Math.min(currentStacks + 1, 5);

        // ---------- РАСЧЁТ МОДИФИКАТОРА УРОНА ----------

        // maxPenalty — штраф первого удара
        // lvl 1: -1.0
        // lvl 5: -3.0
        float maxPenalty = -(0.5f * level + 0.5f);

        // step таков, чтобы:
        // stack 1: maxPenalty
        // stack 3: 0
        // stack 5: -maxPenalty (зеркальный бонус)
        float step = -maxPenalty / 2.0f;

        float modifier = maxPenalty + (nextStacks - 1) * step;

        // положительные значения усиливаем ×1.5
        if (modifier > 0f) {
            modifier *= 1.5f;
        }

        float amount = event.getAmount() + modifier;
        if (amount < 0f) amount = 0f;
        event.setAmount(amount);

        // ---------- ЧАСТИЦЫ ВИЗУАЛА (ОБНОВЛЁННЫЕ) ----------

        if (victim.level() instanceof ServerLevel serverLevel) {
            double x = victim.getX();
            // вместо середины хитбокса — уровень глаз, так видно даже на гигантах
            double y = victim.getEyeY();
            double z = victim.getZ();

            if (modifier > 0f) {
                // Плюс урон — огонь. Больше стаков → чуть больше частиц, но без ада.
                int count = 2 + nextStacks * 2; // 4..12
                if (count > 10) count = 10;

                serverLevel.sendParticles(
                        ParticleTypes.FLAME,
                        x, y, z,
                        count,
                        0.35d, 0.4d, 0.35d, // шире разброс по X/Z и немного по высоте
                        0.01d
                );
            } else if (modifier < 0f) {
                // Минус урон — дым, чуть поплотнее вокруг головы/шеи
                int count = 2 + nextStacks; // 3..7
                if (count > 7) count = 7;

                serverLevel.sendParticles(
                        ParticleTypes.SMOKE,
                        x, y, z,
                        count,
                        0.35d, 0.4d, 0.35d,
                        0.01d
                );
            }
        }

        // сохраняем стеки после удара
        stacks.put(playerId, nextStacks);
    }
}
