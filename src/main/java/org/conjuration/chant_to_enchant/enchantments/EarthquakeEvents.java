package org.conjuration.chant_to_enchant.enchantments;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

import java.util.List;

import static org.conjuration.chant_to_enchant.Chant_to_enchant.MODID;

/**
 * Earthquake I–II (ботинки)
 *
 * — Срабатывает ТОЛЬКО на входящий урон от падения.
 * — Если рядом есть «подходящие» мобы (враждебные или заагренные на носителя), то:
 *      • носитель получает 65% урона (–35% снижения),
 *      • все «подходящие» получают 100% исходного урона,
 *      • «опасные» (враждебные/заагренные) получают ×(1 + 0.5 * уровень) дополнительный множитель: L1=1.5x, L2=2.0x,
 *      • все, кто получил урон, слегка подлетают вверх; чем выше уровень — тем выше подброс.
 * — Радиус: L1 = 6, L2 = 8 (6 + 2*(lvl-1)).
 */
public class EarthquakeEvents {

    private static final ResourceLocation EARTHQUAKE_ID =
            ResourceLocation.fromNamespaceAndPath(MODID, "earthquake");
    private static final ResourceKey<Enchantment> EARTHQUAKE_KEY =
            ResourceKey.create(Registries.ENCHANTMENT, EARTHQUAKE_ID);

    // Настройки
    private static final float SELF_DAMAGE_MULTIPLIER = 0.65f; // –35%
    private static final int BASE_RADIUS = 6;
    private static final int RADIUS_PER_LEVEL = 2;

    // Параметры подброса (вверх) и легкого разлёта
    private static final double BASE_VERTICAL_LAUNCH = 0.25;   // базовый «подлёт»
    private static final double PER_LEVEL_VERTICAL    = 0.20;   // прибавка вверх за уровень
    private static final double OUTWARD_PUSH          = 0.25;   // чуть раздвинуть наружу

    // Если регистрируешь вручную через NeoForge.EVENT_BUS.addListener, аннотация не обязательна,
    // но оставить её безопасно, если у тебя уже настроен авто-сабскрайб.
    @SubscribeEvent
    public static void onLivingIncomingDamage(LivingIncomingDamageEvent event) {
        LivingEntity victim = event.getEntity();
        DamageSource source = event.getSource();

        // Интересует только падение
        if (!source.is(DamageTypeTags.IS_FALL)) return;

        int level = getEarthquakeLevelOnFeet(victim);
        if (level <= 0) return;

        // Радиус по уровню
        int radius = BASE_RADIUS + Math.max(0, level - 1) * RADIUS_PER_LEVEL;

        // Собираем подходящих: враждебные или заагренные на носителя
        AABB box = victim.getBoundingBox().inflate(radius);
        List<LivingEntity> targets = victim.level().getEntitiesOfClass(
                LivingEntity.class,
                box,
                le -> isSuitable(le, victim) && le.distanceToSqr(victim) <= (radius * radius + 1)
        );

        if (targets.isEmpty()) return;

        // Фиксируем исходный урон от падения ДО снижения
        float originalFallDamage = event.getAmount();

        // Снижаем входящий урон по носителю
        event.setAmount(originalFallDamage * SELF_DAMAGE_MULTIPLIER);

        // Источник урона «от носителя», чтобы засчитывалось корректно
        DamageSource outSrc = (victim instanceof Player p)
                ? p.damageSources().playerAttack(p)
                : victim.damageSources().mobAttack(victim);

        // Раздаём урон и подбрасываем
        for (LivingEntity t : targets) {
            float dmg = originalFallDamage;
            if (isDangerous(t, victim)) {
                dmg *= (1.0f + 0.5f * level); // L1=1.5x, L2=2.0x
            }
            t.hurt(outSrc, dmg);

            // Небольшой подброс вверх + лёгкий пуш от эпицентра
            Vec3 fromCenter = t.position().subtract(victim.position());
            Vec3 outward = fromCenter.lengthSqr() > 1.0e-4 ? fromCenter.normalize().scale(OUTWARD_PUSH) : Vec3.ZERO;

            double vBoost = BASE_VERTICAL_LAUNCH + PER_LEVEL_VERTICAL * level;
            Vec3 cur = t.getDeltaMovement();

            // Не уменьшаем текущий вертикальный импульс, только гарантируем минимум
            double newVy = Math.max(cur.y, vBoost);
            t.setDeltaMovement(cur.x + outward.x, newVy, cur.z + outward.z);
            t.hurtMarked = true; // чтобы клиент сразу обновил нокбэк/движение
        }
    }

    private static int getEarthquakeLevelOnFeet(LivingEntity le) {
        ItemStack boots = le.getItemBySlot(EquipmentSlot.FEET);
        if (boots.isEmpty()) return 0;

        Registry<Enchantment> reg = le.level().registryAccess().registryOrThrow(Registries.ENCHANTMENT);
        Holder<Enchantment> holder = reg.getHolder(EARTHQUAKE_KEY).orElse(null);
        if (holder == null) return 0;

        return boots.getEnchantmentLevel(holder);
    }

    // Подходит под условие триггера: враждебный или заагрен на носителя
    private static boolean isSuitable(LivingEntity target, LivingEntity wearer) {
        if (target == wearer || !target.isAlive()) return false;
        if (target instanceof Monster) return true;
        if (target instanceof Mob mob) {
            Entity aggro = mob.getTarget();
            return aggro == wearer;
        }
        return false;
    }

    // «Опасная» цель — те же, что и критерий срабатывания
    private static boolean isDangerous(LivingEntity target, LivingEntity wearer) {
        if (target instanceof Monster) return true;
        if (target instanceof Mob mob) {
            return mob.getTarget() == wearer;
        }
        return false;
    }
}
