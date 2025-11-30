package org.conjuration.chant_to_enchant.enchantments;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import org.conjuration.chant_to_enchant.Chant_to_enchant;
// если ModEnchantments лежит в другом пакете – поменяй импорт
import org.conjuration.chant_to_enchant.enchantments.ModEnchantments;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class GustEvents {

    // шанс активации шквала у каждого атакующего
    private static final Map<UUID, Integer> gustChance = new HashMap<>();

    // последний тик, когда проверяли шанс (кд 0.5 сек по tickCount атакующего)
    private static final Map<UUID, Integer> lastCheckTick = new HashMap<>();

    // активный шквал (цель → инстанс)
    private static final Map<UUID, GustInstance> activeGusts = new HashMap<>();

    private static final int BASE_CHANCE = 10;
    private static final int HIT_INTERVAL_TICKS = 15; // 0.75 сек при 20 тиках
    private static final double SPREAD_RADIUS = 6.0;

    private record GustInstance(
            UUID attackerId,
            float damagePerHit,
            int remainingHits,
            int ticksUntilNextHit
    ) {}

    // =========================
    // 1) Активация при уроне
    // =========================
    public static void onDamage(LivingIncomingDamageEvent event) {
        Entity src = event.getSource().getEntity();
        if (!(src instanceof LivingEntity attacker)) return;

        LivingEntity target = event.getEntity();
        if (target.level().isClientSide()) return;

        float dmg = event.getAmount();
        // урон должен реально пройти
        if (dmg <= 0) return;

        // достаём энчант через Holder
        RegistryAccess access = attacker.level().registryAccess();
        var enchantRegistry = access.lookupOrThrow(Registries.ENCHANTMENT);
        var gustHolder = enchantRegistry.getOrThrow(ModEnchantments.GUST);

        int level = EnchantmentHelper.getEnchantmentLevel(gustHolder, attacker);
        if (level <= 0) return;

        UUID atkId = attacker.getUUID();

        // кд 0.5 сек по tickCount атакующего
        int currentTick = attacker.tickCount;
        int lastTick = lastCheckTick.getOrDefault(atkId, -1000);
        if (currentTick - lastTick < 10) {
            return;
        }
        lastCheckTick.put(atkId, currentTick);

        int chance = gustChance.getOrDefault(atkId, BASE_CHANCE);

        if (attacker.level().random.nextInt(100) < chance) {
            // успех — сбрасываем шанс и запускаем шквал
            gustChance.put(atkId, BASE_CHANCE);
            startGust(attacker, target, dmg, level);
        } else {
            // провал — шанс растёт до 100%
            gustChance.put(atkId, Math.min(100, chance + 10));
        }
    }

    private static void startGust(LivingEntity attacker, LivingEntity target, float triggeringDamage, int level) {
        float dmg = triggeringDamage * 0.35f;
        int hits = 3 + level;

        activeGusts.put(
                target.getUUID(),
                new GustInstance(attacker.getUUID(), dmg, hits, HIT_INTERVAL_TICKS)
        );

        Chant_to_enchant.LOGGER.info("[Gust] start on {}, hits={}, dmgPerHit={}",
                target.getName().getString(), hits, dmg);

        spawnActivationParticles(target);
        playGustSound(target);
    }

    // =========================
    // 2) Тики сущностей
    // =========================
    public static void onEntityTick(EntityTickEvent.Pre event) {
        Entity e = event.getEntity();
        if (!(e instanceof LivingEntity target)) return;
        if (target.level().isClientSide()) return;
        if (!(target.level() instanceof ServerLevel level)) return;

        UUID targetId = target.getUUID();
        GustInstance inst = activeGusts.get(targetId);
        if (inst == null) return;

        LivingEntity attacker = (LivingEntity) level.getEntity(inst.attackerId());
        if (attacker == null || !attacker.isAlive()) {
            // атакующий умер/исчез — гасим эффект
            activeGusts.remove(targetId);
            return;
        }

        // если цель умерла ДО того, как все удары были сделаны —
        // перераспределяем оставшиеся удары на всех противников вокруг
        if (!target.isAlive()) {
            int remaining = inst.remainingHits();
            if (remaining > 0) {
                spreadRemainingHits(level, target, attacker, inst.damagePerHit(), remaining);
            }
            activeGusts.remove(targetId);
            return;
        }

        int ticksLeft = inst.ticksUntilNextHit() - 1;
        if (ticksLeft > 0) {
            // просто ждём следующего удара
            activeGusts.put(targetId, new GustInstance(
                    inst.attackerId(),
                    inst.damagePerHit(),
                    inst.remainingHits(),
                    ticksLeft
            ));
            return;
        }

        // настало время удара по текущей цели
        DamageSource src = level.damageSources().magic(); // гарантированно бьёт
        target.hurt(src, inst.damagePerHit());

        Chant_to_enchant.LOGGER.info("[Gust] hit {} for {} (left={})",
                target.getName().getString(), inst.damagePerHit(), inst.remainingHits() - 1);

        spawnHitParticles(target);
        playGustSound(target);

        int hitsLeft = inst.remainingHits() - 1;
        if (hitsLeft <= 0) {
            activeGusts.remove(targetId);
        } else {
            activeGusts.put(targetId, new GustInstance(
                    inst.attackerId(),
                    inst.damagePerHit(),
                    hitsLeft,
                    HIT_INTERVAL_TICKS
            ));
        }
    }

    // перераспределяем оставшиеся удары на всех врагов вокруг
    private static void spreadRemainingHits(ServerLevel level,
                                            LivingEntity deadTarget,
                                            LivingEntity attacker,
                                            float damagePerHit,
                                            int remainingHits) {

        AABB box = deadTarget.getBoundingBox().inflate(SPREAD_RADIUS);
        List<LivingEntity> nearby = level.getEntitiesOfClass(LivingEntity.class, box,
                le -> le.isAlive()
                        && !le.getUUID().equals(attacker.getUUID())
                        && !le.getUUID().equals(deadTarget.getUUID())
        );

        if (nearby.isEmpty()) {
            Chant_to_enchant.LOGGER.info("[Gust] target {} died with {} hits left, no enemies nearby",
                    deadTarget.getName().getString(), remainingHits);
            return;
        }

        Chant_to_enchant.LOGGER.info("[Gust] target {} died with {} hits left, spreading to {} entities",
                deadTarget.getName().getString(), remainingHits, nearby.size());

        for (LivingEntity newTarget : nearby) {
            activeGusts.put(
                    newTarget.getUUID(),
                    new GustInstance(attacker.getUUID(), damagePerHit, remainingHits, HIT_INTERVAL_TICKS)
            );
            spawnActivationParticles(newTarget);
            playGustSound(newTarget);
        }
    }

    // =========================
    // Визуал и звуки
    // =========================
    private static void spawnActivationParticles(LivingEntity target) {
        if (!(target.level() instanceof ServerLevel lvl)) return;

        for (int i = 0; i < 15; i++) {
            lvl.sendParticles(
                    ParticleTypes.POOF,
                    target.getX(),
                    target.getY() + target.getBbHeight() / 2,
                    target.getZ(),
                    1,
                    0.3, 0.4, 0.3,
                    0.02
            );
        }
    }

    private static void spawnHitParticles(LivingEntity target) {
        if (!(target.level() instanceof ServerLevel lvl)) return;

        lvl.sendParticles(
                ParticleTypes.SWEEP_ATTACK,
                target.getX(),
                target.getY() + target.getBbHeight() * 0.6,
                target.getZ(),
                1,
                0,
                0,
                0,
                0
        );
    }

    private static void playGustSound(LivingEntity target) {
        target.level().playSound(
                null,
                target.blockPosition(),
                SoundEvents.PLAYER_ATTACK_SWEEP,
                SoundSource.PLAYERS,
                0.6f,
                1.4f
        );
    }
}
