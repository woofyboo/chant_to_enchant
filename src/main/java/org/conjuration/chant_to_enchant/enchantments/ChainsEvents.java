package org.conjuration.chant_to_enchant.enchantments;

import org.conjuration.chant_to_enchant.Chant_to_enchant;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.fml.common.EventBusSubscriber;

import java.util.*;

/**
 * Chains I-III — зачар на оружие ближнего боя.
 *
 * При попадании заряженной атакой:
 *  - шанс 10% / 20% / 30%;
 *  - подтягивает к центру до 5 ближайших мобов;
 *  - затем на 1 / 1.5 / 2 секунды "связывает" их:
 *      * MOVEMENT_SPEED = 0,
 *      * ATTACK_DAMAGE = 0,
 *      * урон от них по игроку полностью отменяется.
 *
 * Ограничения:
 *  - У КАЖДОГО игрока может быть только одна активная цепь.
 *  - После окончания эффекта у этого игрока есть КД 1 секунда (20 тиков),
 *    но другие игроки не страдают и могут кастовать свои цепи.
 */
@EventBusSubscriber(modid = Chant_to_enchant.MODID)
public class ChainsEvents {

    private static final ResourceLocation CHAINS_ID =
            ResourceLocation.fromNamespaceAndPath(Chant_to_enchant.MODID, "chains");

    // Кэшированный Holder зачара
    private static Holder<Enchantment> CHAINS_HOLDER = null;

    /**
     * Данные по мобу в цепях:
     *  - levelKey          — в каком измерении он живёт
     *  - pullUntilTick     — до какого тика мира его тянем
     *  - endTick           — когда эффект заканчивается
     *  - anchor            — центр стяжки
     *  - originalMoveSpeed — исходная MOVEMENT_SPEED
     *  - originalDamage    — исходный ATTACK_DAMAGE
     */
    private static final class ChainInfo {
        final ResourceKey<Level> levelKey;
        final long pullUntilTick;
        final long endTick;
        final Vec3 anchor;
        final double originalMoveSpeed;
        final double originalDamage;

        ChainInfo(ResourceKey<Level> levelKey,
                  long pullUntilTick,
                  long endTick,
                  Vec3 anchor,
                  double originalMoveSpeed,
                  double originalDamage) {
            this.levelKey = levelKey;
            this.pullUntilTick = pullUntilTick;
            this.endTick = endTick;
            this.anchor = anchor;
            this.originalMoveSpeed = originalMoveSpeed;
            this.originalDamage = originalDamage;
        }
    }

    // UUID моба -> инфа по цепям
    private static final Map<UUID, ChainInfo> CHAINED_MOBS = new HashMap<>();

    /**
     * Перс-игроковый кулдаун:
     *  player UUID -> gameTime, до которого этот игрок НЕ может запускать новые цепи.
     *  Ставим туда endTick + 20 (1 секунда после конца эффекта).
     */
    private static final Map<UUID, Long> PLAYER_CHAIN_COOLDOWN = new HashMap<>();

    // =========================================================
    //                     EVENT HANDLERS
    // =========================================================

    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        LivingEntity victim = event.getEntity();
        Level lvl = victim.level();
        if (!(lvl instanceof ServerLevel level)) return;

        long gameTime = level.getGameTime();
        DamageSource src = event.getSource();
        Entity srcEnt = src.getEntity();

        // 1) Если атакует моб, который в цепях — урон просто не проходит
        if (srcEnt instanceof LivingEntity attackerLiving) {
            ChainInfo info = CHAINED_MOBS.get(attackerLiving.getUUID());
            if (info != null
                    && info.levelKey == level.dimension()
                    && gameTime <= info.endTick) {
                event.setCanceled(true);
                return;
            }
        }

        // 2) Триггер Chains от игрока
        if (!(srcEnt instanceof Player player)) {
            return;
        }

        ItemStack weapon = player.getMainHandItem();
        if (weapon.isEmpty()) return;

        int chainsLevel = getChainsLevel(level, weapon);
        if (chainsLevel <= 0) return;

        // Только полностью заряженная атака
        float charge = player.getAttackStrengthScale(0.5f);
        if (charge < 0.99f) return;

        // Персональный кулдаун для игрока
        long cdUntil = PLAYER_CHAIN_COOLDOWN.getOrDefault(player.getUUID(), 0L);
        if (gameTime < cdUntil) {
            // Для этого игрока цепи ещё на КД
            return;
        }

        // Шанс по уровню
        float chance = switch (chainsLevel) {
            case 1 -> 0.10f;
            case 2 -> 0.20f;
            case 3 -> 0.30f;
            default -> 0.30f;
        };

        if (level.getRandom().nextFloat() >= chance) {
            return;
        }

        // Группа вокруг жертвы
        ServerLevel sLevel = (ServerLevel) level;
        List<LivingEntity> group = findGroupAround(sLevel, victim, player, 5);
        if (group.size() <= 1) {
            return; // некого стягивать
        }

        // Длительность эффекта
        int durationTicks = switch (chainsLevel) {
            case 1 -> (int) (20 * 1.0f);   // 1 сек
            case 2 -> (int) (20 * 1.5f);   // 1.5 сек
            case 3 -> (int) (20 * 2.0f);   // 2 сек
            default -> 40;
        };

        int pullTicks = 5; // тиков даём им подтянуться

        Vec3 center = victim.position();
        long pullUntil = gameTime + pullTicks;
        long endTick = gameTime + durationTicks;
        ResourceKey<Level> levelKey = level.dimension();

        // --- Звук установки цепей ---
        level.playSound(
                null,
                center.x, center.y, center.z,
                SoundEvents.CHAIN_PLACE,
                SoundSource.PLAYERS,
                1.0F,
                1.0F
        );

        for (LivingEntity le : group) {
            double originalMoveSpeed;
            double originalDamage;

            ChainInfo old = CHAINED_MOBS.get(le.getUUID());
            if (old != null && old.levelKey == levelKey) {
                // Если уже были в карте — не теряем исходные значения
                originalMoveSpeed = old.originalMoveSpeed;
                originalDamage = old.originalDamage;
            } else {
                originalMoveSpeed = -1.0D;
                originalDamage = -1.0D;

                if (le instanceof Mob mob) {
                    AttributeInstance ms = mob.getAttribute(Attributes.MOVEMENT_SPEED);
                    if (ms != null) {
                        originalMoveSpeed = ms.getBaseValue();
                    }
                    AttributeInstance ad = mob.getAttribute(Attributes.ATTACK_DAMAGE);
                    if (ad != null) {
                        originalDamage = ad.getBaseValue();
                    }
                }
            }

            // Режем атрибуты прямо сейчас
            if (le instanceof Mob mob) {
                AttributeInstance ms = mob.getAttribute(Attributes.MOVEMENT_SPEED);
                if (ms != null) {
                    ms.setBaseValue(0.0D);
                }
                AttributeInstance ad = mob.getAttribute(Attributes.ATTACK_DAMAGE);
                if (ad != null) {
                    ad.setBaseValue(0.0D);
                }
                mob.getNavigation().stop();
                mob.setTarget(null);
            }

            CHAINED_MOBS.put(
                    le.getUUID(),
                    new ChainInfo(levelKey, pullUntil, endTick, center, originalMoveSpeed, originalDamage)
            );
        }

        // Ставим КД для ЭТОГО игрока: пока идёт эффект + ещё 1 сек после
        PLAYER_CHAIN_COOLDOWN.put(player.getUUID(), endTick + 20);
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (CHAINED_MOBS.isEmpty()) return;

        long time = level.getGameTime();
        ResourceKey<Level> currentKey = level.dimension();

        Iterator<Map.Entry<UUID, ChainInfo>> it = CHAINED_MOBS.entrySet().iterator();

        while (it.hasNext()) {
            Map.Entry<UUID, ChainInfo> ent = it.next();
            UUID id = ent.getKey();
            ChainInfo info = ent.getValue();

            // Запись не для этого измерения — пропускаем
            if (info.levelKey != currentKey) {
                continue;
            }

            Entity e = level.getEntity(id);
            if (!(e instanceof LivingEntity le)) {
                // Сущность пропала — просто чистим запись
                it.remove();
                continue;
            }

            // Эффект закончился — возвращаем атрибуты
            if (time >= info.endTick) {
                restoreMobState(le, info);
                it.remove();
                continue;
            }

            Vec3 pos = le.position();
            Vec3 anchor = info.anchor;

            if (time < info.pullUntilTick) {
                // ФАЗА ПРИТЯЖЕНИЯ — мягко тянем к центру
                Vec3 dir = anchor.subtract(pos);
                double distSq = dir.lengthSqr();
                if (distSq > 0.01) {
                    Vec3 norm = dir.normalize();
                    double pullStrength = 0.25;
                    Vec3 add = norm.scale(pullStrength);
                    Vec3 cur = le.getDeltaMovement();
                    le.setDeltaMovement(cur.add(add.x, 0.0, add.z));
                    le.hurtMarked = true;
                }
            } else {
                // ФАЗА "стоят в цепях" — гасим остаточную скорость
                Vec3 cur = le.getDeltaMovement();
                le.setDeltaMovement(cur.x * 0.2, cur.y, cur.z * 0.2);
                le.hurtMarked = true;
            }
        }
    }

    // =========================================================
    //                        HELPERS
    // =========================================================

    private static Holder<Enchantment> getChainsHolder(ServerLevel level) {
        if (CHAINS_HOLDER != null) return CHAINS_HOLDER;

        Registry<Enchantment> reg = level.registryAccess().registryOrThrow(Registries.ENCHANTMENT);
        ResourceKey<Enchantment> key = ResourceKey.create(Registries.ENCHANTMENT, CHAINS_ID);

        reg.getHolder(key).ifPresent(h -> CHAINS_HOLDER = h);
        return CHAINS_HOLDER;
    }

    private static int getChainsLevel(Level level, ItemStack stack) {
        if (stack.isEmpty()) return 0;
        if (!(level instanceof ServerLevel serverLevel)) return 0;

        Holder<Enchantment> h = getChainsHolder(serverLevel);
        if (h == null) return 0;
        return EnchantmentHelper.getItemEnchantmentLevel(h, stack);
    }

    /**
     * Ищем до maxCount ближайших живых вокруг жертвы, кроме атакующего.
     */
    private static List<LivingEntity> findGroupAround(ServerLevel level,
                                                      LivingEntity victim,
                                                      LivingEntity attacker,
                                                      int maxCount) {
        double radius = 6.0;
        Vec3 center = victim.position();
        AABB box = AABB.unitCubeFromLowerCorner(center).inflate(radius);

        List<LivingEntity> list = level.getEntitiesOfClass(
                LivingEntity.class,
                box,
                e -> e.isAlive() && e != attacker
        );

        if (!list.contains(victim)) {
            list.add(victim);
        }

        list.sort(Comparator.comparingDouble(e -> e.distanceTo(victim)));

        if (list.size() > maxCount) {
            list = new ArrayList<>(list.subList(0, maxCount));
        }

        return list;
    }

    private static void restoreMobState(LivingEntity le, ChainInfo info) {
        if (!(le instanceof Mob mob)) {
            return;
        }

        AttributeInstance ms = mob.getAttribute(Attributes.MOVEMENT_SPEED);
        if (ms != null) {
            if (info.originalMoveSpeed >= 0.0D) {
                ms.setBaseValue(info.originalMoveSpeed);
            } else if (ms.getBaseValue() == 0.0D) {
                // fallback, если вдруг не сохранили оригинал
                ms.setBaseValue(0.25D);
            }
        }

        AttributeInstance ad = mob.getAttribute(Attributes.ATTACK_DAMAGE);
        if (ad != null) {
            if (info.originalDamage >= 0.0D) {
                ad.setBaseValue(info.originalDamage);
            } else if (ad.getBaseValue() == 0.0D) {
                ad.setBaseValue(2.0D);
            }
        }

        mob.getNavigation().stop();
    }
}
