package org.conjuration.chant_to_enchant.enchantments;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.conjuration.chant_to_enchant.Chant_to_enchant;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class StealthEvents {

    // таймер до входа в стелс (задержка перед невидимостью)
    private static final Map<UUID, Integer> vanishTimers = new HashMap<>();
    // кулдаун после прерывания (урон / атака)
    private static final Map<UUID, Integer> cooldowns = new HashMap<>();
    // флаг "сейчас в стелсе"
    private static final Map<UUID, Boolean> isStealthed = new HashMap<>();
    // запоминаем уровень зачара, с которым зашли в стелс (для корректного кулдауна)
    private static final Map<UUID, Integer> activeLevels = new HashMap<>();
    // сколько тиков подряд игрок НЕ крадётся (для защиты от флика)
    private static final Map<UUID, Integer> unsneakTicks = new HashMap<>();

    private static final ResourceKey<Enchantment> STEALTH_KEY = ModEnchantments.STEALTH;

    private static int getDelay(int lvl) {
        return switch (lvl) {
            case 1 -> 40; // 2 сек
            case 2 -> 20; // 1 сек
            case 3 -> 10; // 0.5 сек
            default -> 40;
        };
    }

    private static int getCooldownTicks(int lvl) {
        return switch (lvl) {
            case 1 -> 240; // 12 сек
            case 2 -> 160; // 8 сек
            case 3 -> 80;  // 4 сек
            default -> 240;
        };
    }

    /**
     * Получаем уровень Stealth ТОЛЬКО со штанов.
     * ВАЖНО: EnchantmentHelper здесь ждёт Holder<Enchantment>.
     */
    private static int getStealthLevel(LivingEntity entity) {
        Level level = entity.level();
        if (level.isClientSide) return 0;

        HolderLookup.RegistryLookup<Enchantment> enchRegistry =
                level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);

        Holder<Enchantment> stealthHolder = enchRegistry.getOrThrow(STEALTH_KEY);

        ItemStack legs = entity.getItemBySlot(EquipmentSlot.LEGS);
        if (legs.isEmpty()) return 0;

        return EnchantmentHelper.getItemEnchantmentLevel(stealthHolder, legs);
    }

    // === ТИК МИРА ===
    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        Level level = event.getLevel();
        if (level.isClientSide) return;

        for (Player player : level.players()) {
            tickStealthFor(player);
        }
    }

    private static void tickStealthFor(LivingEntity entity) {
        Level level = entity.level();
        if (level.isClientSide) return;

        UUID id = entity.getUUID();

        // тикаем кулдауны (как только доходит до 0 — убираем запись)
        cooldowns.computeIfPresent(id, (k, v) -> v > 0 ? v - 1 : null);

        int lvl = getStealthLevel(entity);
        boolean sneaking = entity.isShiftKeyDown();

        // если вообще нет зачара → всё сразу сбрасываем
        if (lvl <= 0) {
            vanishTimers.remove(id);
            unsneakTicks.remove(id);
            stopStealth(entity);
            return;
        }

        // обновляем счётчик "не крадётся"
        if (!sneaking) {
            int c = unsneakTicks.getOrDefault(id, 0) + 1;
            unsneakTicks.put(id, c);
        } else {
            unsneakTicks.remove(id);
        }

        // если уже в стелсе и игрок перестал красться НАДОЛГО (3+ тика) → выходим
        if (isStealthed.getOrDefault(id, false)) {
            if (!sneaking && unsneakTicks.getOrDefault(id, 0) >= 3) {
                stopStealth(entity);
                return;
            }

            // всё ещё в стелсе → поддерживаем невидимость и сбрасываем агро
            keepInvisible(entity);
            return;
        }

        // ещё НЕ в стелсе:
        // если не крадётся — просто сброс таймера входа и выходим
        if (!sneaking) {
            vanishTimers.remove(id);
            return;
        }

        // есть кулдаун → нельзя входить в стелс
        if (cooldowns.containsKey(id)) {
            vanishTimers.remove(id);
            return;
        }

        // тик задержки перед входом в стелс
        int newTimer = vanishTimers.getOrDefault(id, getDelay(lvl)) - 1;
        if (newTimer <= 0) {
            vanishTimers.remove(id);
            startStealth(entity, lvl);
        } else {
            vanishTimers.put(id, newTimer);
        }
    }

    private static void startStealth(LivingEntity e, int lvl) {
        UUID id = e.getUUID();
        isStealthed.put(id, true);
        activeLevels.put(id, Math.max(1, lvl));

        // эффект "дымовой гранаты" при исчезновении
        playSmokeEffect(e, true);

        // только флаг сущности, без зелья:
        // так пропадает и модель, и хитбокс-рендер (клиент прячет броню/предмет через миксины)
        e.setInvisible(true);
    }

    /**
     * Проверка: должен ли этот моб "игнорировать" стелс-игрока,
     * исходя из условия: max HP моба <= max HP игрока.
     */
    public static boolean shouldMobIgnoreStealthedPlayer(LivingEntity mobEntity, LivingEntity player) {
        if (!(mobEntity instanceof Mob mob)) {
            return false;
        }

        // только враждебные сущности (категория MONSTER)
        if (mob.getType().getCategory() != MobCategory.MONSTER) {
            return false;
        }

        float mobMax = mob.getMaxHealth();
        float playerMax = player.getMaxHealth();

        return mobMax <= playerMax;
    }

    private static void keepInvisible(LivingEntity e) {
        // если по какой-то причине флаг сбился — возвращаем
        if (!e.isInvisible()) {
            e.setInvisible(true);
        }

        // гасим только тех мобов, которые ДОЛЖНЫ нас игнорировать (по правилу ХП)
        e.level().getEntitiesOfClass(
                        Mob.class,
                        e.getBoundingBox().inflate(16.0D),
                        mob -> shouldMobIgnoreStealthedPlayer(mob, e)
                )
                .forEach(mob -> {
                    if (mob.getTarget() == e) {
                        mob.setTarget(null);
                        mob.setAggressive(false);
                    }

                    if (mob.getLastHurtByMob() == e) {
                        mob.setLastHurtByMob(null);
                    }

                    mob.getNavigation().stop();
                    mob.getBrain().clearMemories();

                    // чтобы не пялился на нас даже по "случайным" look-таскам
                    mob.getLookControl().setLookAt(
                            mob.getX(),
                            mob.getEyeY(),
                            mob.getZ()
                    );
                });
    }

    public static void stopStealth(LivingEntity e) {
        UUID id = e.getUUID();
        if (!isStealthed.getOrDefault(id, false)) return;

        isStealthed.remove(id);
        vanishTimers.remove(id);
        activeLevels.remove(id);
        unsneakTicks.remove(id);

        // эффект дымовой гранаты при появлении
        playSmokeEffect(e, false);

        // возвращаем видимость только если это именно наш стелс
        e.setInvisible(false);
    }

    // === УРОН (снятие стелса и кулдаун) ===
    @SubscribeEvent
    public static void onDamage(LivingIncomingDamageEvent event) {
        LivingEntity victim = event.getEntity();
        Level level = victim.level();
        if (level.isClientSide) return;

        // 1) жертва была в стелсе
        {
            UUID id = victim.getUUID();
            if (isStealthed.getOrDefault(id, false)) {
                int lvl = activeLevels.getOrDefault(id, getStealthLevel(victim));
                stopStealth(victim);
                if (lvl > 0) {
                    cooldowns.put(id, getCooldownTicks(lvl));
                }
            }
        }

        // 2) атакующий был в стелсе
        if (event.getSource().getEntity() instanceof LivingEntity attacker) {
            UUID id = attacker.getUUID();
            if (isStealthed.getOrDefault(id, false)) {
                int lvl = activeLevels.getOrDefault(id, getStealthLevel(attacker));
                stopStealth(attacker);
                if (lvl > 0) {
                    cooldowns.put(id, getCooldownTicks(lvl));
                }
            }
        }
    }

    public static boolean isEntityStealthed(LivingEntity e) {
        return isStealthed.getOrDefault(e.getUUID(), false);
    }

    /**
     * Дым + тихий взрыв фейерверка при входе/выходе из стелса.
     * entering == true -> исчезновение
     * entering == false -> появление
     */
    private static void playSmokeEffect(LivingEntity e, boolean entering) {
        Level level = e.level();
        if (level.isClientSide) return;
        if (!(level instanceof ServerLevel serverLevel)) return;

        double x = e.getX();
        double y = e.getY() + e.getBbHeight() * 0.5;
        double z = e.getZ();

        int count = entering ? 80 : 60;
        double spread = 1.5;

        // облако дыма вокруг игрока
        serverLevel.sendParticles(
                ParticleTypes.CLOUD,
                x, y, z,
                count,
                spread, spread * 0.5, spread,
                0.02
        );

        // тихий взрыв фейерверка, чтобы чуть привлечь внимание
        float volume = 0.6f;
        float pitch = 0.9f + (serverLevel.getRandom().nextFloat() - 0.5f) * 0.2f;

        serverLevel.playSound(
                null,
                x, y, z,
                SoundEvents.FIREWORK_ROCKET_BLAST,
                SoundSource.PLAYERS,
                volume,
                pitch
        );
    }
}
