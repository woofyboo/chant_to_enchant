package org.conjuration.chant_to_enchant.enchantments;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.conjuration.chant_to_enchant.Chant_to_enchant.MODID;

public class WindbladeEvents {

    // ===== полёт =====
    private static final double MAX_DISTANCE = 16.0;
    private static final double STEP_PER_TICK = 0.8;
    private static final int    MAX_TICKS = (int)Math.ceil(MAX_DISTANCE / STEP_PER_TICK);

    // ===== геометрия =====
    private static final double LATERAL_HALF = 2.5;    // половина ширины по XZ (итого ~5 блоков)
    private static final double VERTICAL_HALF = 0.75;  // «тонкая» полоса по Y

    // ===== толчки =====
    private static final double KNOCK_PATH_BASE = 0.55;
    private static final double KNOCK_BLAST_BASE = 1.0;
    private static final double KNOCK_UP_ADD = 0.15;

    // ===== прочность =====
    private static final int    DURABILITY_COST = 4;
    private static final int PARTICLE_TICK_INTERVAL = 5; // сколько тиков ждать между "кадрами" частиц
// ...


    // ===== рокетджамп =====
    private static final double RJ_DOWN_DOT_Y = -0.55;
    private static final double RJ_UP_FORCE   = 0.6;
    private static final double RJ_BACK_FORCE = 0.25;


    // ===== частицы (урезано до 3 штук на шаг) =====
    private static final double PARTICLE_JITTER = 0.02;

    // ===== ключи зачар =====
    private static final ResourceLocation WIND_ID = ResourceLocation.fromNamespaceAndPath(MODID, "windblade");
    private static final ResourceKey<Enchantment> WIND_KEY = ResourceKey.create(Registries.ENCHANTMENT, WIND_ID);

    // ванильный Sweeping Edge для буста урона волны
    private static final ResourceKey<Enchantment> SWEEP_KEY =
            ResourceKey.create(Registries.ENCHANTMENT, ResourceLocation.withDefaultNamespace("sweeping_edge"));

    // активные лезвия по мирам
    private static final Map<ServerLevel, List<Blade>> ACTIVE = new ConcurrentHashMap<>();

    // анти-даблспавн
    private static final Map<UUID, Integer> LAST_HIT_TICK = new WeakHashMap<>();
    private static final Map<UUID, Boolean> WAS_SWINGING = new WeakHashMap<>();
    private static final Map<UUID, Integer> LAST_AIR_SPAWN_TICK = new WeakHashMap<>();

    private static Holder<Enchantment> holder(ServerLevel level, ResourceKey<Enchantment> key) {
        Registry<Enchantment> reg = level.registryAccess().registryOrThrow(Registries.ENCHANTMENT);
        return reg.getHolder(key).orElse(null);
    }
    private static int getLevelOn(ServerLevel level, ItemStack stack, ResourceKey<Enchantment> key) {
        Holder<Enchantment> h = holder(level, key);
        return (h == null) ? 0 : stack.getEnchantmentLevel(h);
    }
    private static int getWindLevel(ServerLevel level, ItemStack stack) {
        return getLevelOn(level, stack, WIND_KEY);
    }
    private static int getSweepingLevel(ServerLevel level, ItemStack stack) {
        return getLevelOn(level, stack, SWEEP_KEY);
    }

    // ================= События =================

    @SubscribeEvent
    public static void onAttackEntity(AttackEntityEvent event) {
        if (!(event.getEntity() instanceof Player p)) return;
        if (!(event.getTarget() instanceof LivingEntity)) return;
        if (!(p.level() instanceof ServerLevel level)) return;

        ItemStack weapon = p.getMainHandItem();
        if (getWindLevel(level, weapon) <= 0) return;
        if (p.getAttackStrengthScale(0f) < 1.0f) return;

        LAST_HIT_TICK.put(p.getUUID(), level.getServer().getTickCount());
        spawnBladeCombat(level, p, weapon);
    }

    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        Player p = event.getEntity();
        if (!(p.level() instanceof ServerLevel level)) return;

        ItemStack weapon = p.getMainHandItem();
        if (getWindLevel(level, weapon) <= 0) return;
        if (p.getAttackStrengthScale(0f) < 1.0f) return;

        Integer hitTick = LAST_HIT_TICK.get(p.getUUID());
        if (hitTick != null && hitTick == level.getServer().getTickCount()) return;

        spawnBladeVisual(level, p, weapon);
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof Player p)) return;
        if (!(p.level() instanceof ServerLevel level)) return;

        ItemStack weapon = p.getMainHandItem();
        if (getWindLevel(level, weapon) <= 0) return;

        boolean was = WAS_SWINGING.getOrDefault(p.getUUID(), false);
        boolean now = p.swinging;

        if (!was && now) {
            if (p.getAttackStrengthScale(0f) >= 1.0f) {
                int tick = level.getServer().getTickCount();
                Integer hitTick = LAST_HIT_TICK.get(p.getUUID());
                if (hitTick == null || hitTick != tick) {
                    int last = LAST_AIR_SPAWN_TICK.getOrDefault(p.getUUID(), -1000);
                    if (tick - last >= 4) {
                        spawnBladeVisual(level, p, weapon);
                        LAST_AIR_SPAWN_TICK.put(p.getUUID(), tick);
                    }
                }
            }
        }
        WAS_SWINGING.put(p.getUUID(), now);
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        List<Blade> list = ACTIVE.get(level);
        if (list == null || list.isEmpty()) return;

        Iterator<Blade> it = list.iterator();
        while (it.hasNext()) {
            Blade b = it.next();

            if (!isFinite(b.dir) || b.dir.lengthSqr() < 1e-6) {
                blast(level, b, b.pos);
                it.remove();
                continue;
            }
            Vec3 dir = b.dir.normalize();

            if (b.ticksLived++ > MAX_TICKS) {
                blast(level, b, b.pos);
                it.remove();
                continue;
            }

            Vec3 from = b.pos;
            Vec3 to = from.add(dir.scale(STEP_PER_TICK));
            if (!isFinite(from) || !isFinite(to)) {
                blast(level, b, from);
                it.remove();
                continue;
            }

            // рейкаст блоков
            HitResult blockHit;
            try {
                blockHit = level.clip(new ClipContext(
                        from, to,
                        ClipContext.Block.COLLIDER,
                        ClipContext.Fluid.NONE,
                        (Entity) null
                ));
            } catch (Throwable t) {
                blockHit = null;
            }

            if (blockHit != null && blockHit.getType() == HitResult.Type.BLOCK) {
                // визуал в момент удара
                if (b.ticksLived % PARTICLE_TICK_INTERVAL == 0)
                    spawnBladeParticles3(level, from, blockHit.getLocation(), dir, true);

                blast(level, b, blockHit.getLocation());
                it.remove();
                continue;
            }

            b.pos = to;
            b.travelled += STEP_PER_TICK;

            // частицы — только каждые N тиков
            if (b.ticksLived % PARTICLE_TICK_INTERVAL == 0)
                spawnBladeParticles3(level, from, to, dir, false);

            // хитбокс
            AABB lane = makeWideLane(from, to, LATERAL_HALF, VERTICAL_HALF);
            if (lane != null) {
                List<LivingEntity> victims = level.getEntitiesOfClass(LivingEntity.class, lane,
                        e -> e.isAlive() && !Objects.equals(e.getUUID(), b.ownerUUID));

                if (!victims.isEmpty()) {
                    double scale = knockScale(victims.size());
                    for (LivingEntity le : victims) {
                        if (b.hitOnce.add(le.getUUID())) {
                            if (b.canDealDamage && b.damage > 0f) {
                                Player owner = b.getOwner(level);
                                DamageSource src = (owner != null)
                                        ? level.damageSources().playerAttack(owner)
                                        : level.damageSources().generic();
                                le.hurt(src, b.damage);
                            }
                            Vec3 push = dir.scale(KNOCK_PATH_BASE * scale);
                            le.push(push.x, KNOCK_UP_ADD * scale, push.z);
                            le.hurtMarked = true;
                        }
                    }
                }
            }

            if (b.travelled >= MAX_DISTANCE) {
                blast(level, b, b.pos);
                it.remove();
            }
        }
    }

    // ================= Спавн клинка =================

    private static void spawnBladeCombat(ServerLevel level, Player p, ItemStack weapon) {
        // базовый урон игрока
        float baseAttack = (float)p.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);

        // буст от Sweeping Edge: 0..3 уровни → 25,50,75,100% базового
        int sweepLvl = Mth.clamp(getSweepingLevel(level, weapon), 0, 3);
        float pct = 0.25f * (1 + sweepLvl);     // 0.25, 0.50, 0.75, 1.00
        pct = Mth.clamp(pct, 0.0f, 1.0f);

        float damage = Math.max(1f, baseAttack * pct);

        Vec3 look = p.getLookAngle();
        if (!isFinite(look) || look.lengthSqr() < 1e-6) return;
        Vec3 dir = look.normalize();

        Vec3 start = bladeStartPoint(p, dir);
        Blade blade = new Blade(p.getUUID(), start, dir, damage, true);
        track(level, blade);

        tryApplyRocketJump(p, dir);

        level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 0.8f, 1.25f);
        tryHurtWeapon(p, weapon, DURABILITY_COST);
    }

    private static void spawnBladeVisual(ServerLevel level, Player p, ItemStack weapon) {
        Vec3 look = p.getLookAngle();
        if (!isFinite(look) || look.lengthSqr() < 1e-6) return;
        Vec3 dir = look.normalize();

        Vec3 start = bladeStartPoint(p, dir);
        Blade blade = new Blade(p.getUUID(), start, dir, 0f, false);
        track(level, blade);

        tryApplyRocketJump(p, dir);

        level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 0.6f, 1.4f);
        tryHurtWeapon(p, weapon, DURABILITY_COST);
    }

    private static Vec3 bladeStartPoint(Player p, Vec3 lookNorm) {
        return new Vec3(
                p.getX() + lookNorm.x * 1.0,
                p.getY() + p.getBbHeight() * 0.7,
                p.getZ() + lookNorm.z * 1.0
        );
    }

    private static void tryApplyRocketJump(Player p, Vec3 lookNorm) {
        if (lookNorm.y < RJ_DOWN_DOT_Y) {
            Vec3 back = new Vec3(-lookNorm.x * RJ_BACK_FORCE, RJ_UP_FORCE, -lookNorm.z * RJ_BACK_FORCE);
            p.push(back.x, back.y, back.z);
            p.hurtMarked = true;
        }
    }

    private static void track(ServerLevel level, Blade b) {
        ACTIVE.computeIfAbsent(level, k -> new ArrayList<>()).add(b);
    }

    // ================= Взрыв на конце =================

    private static void blast(ServerLevel level, Blade b, Vec3 pos) {
        if (!isFinite(pos)) return;
        double radius = 2.5;
        AABB box = new AABB(
                pos.x - radius, pos.y - 1.0, pos.z - radius,
                pos.x + radius, pos.y + 1.5, pos.z + radius
        );

        List<LivingEntity> victims = level.getEntitiesOfClass(LivingEntity.class, box,
                e -> e.isAlive() && !Objects.equals(e.getUUID(), b.ownerUUID));

        double scale = knockScale(victims.size());
        for (LivingEntity le : victims) {
            Vec3 away = le.position().subtract(pos);
            if (!isFinite(away) || away.lengthSqr() < 1e-6) continue;
            Vec3 push = away.normalize().scale(KNOCK_BLAST_BASE * scale);
            le.push(push.x, KNOCK_UP_ADD * scale, push.z);
            le.hurtMarked = true;
        }

        // скромный «пшик»
        for (int i = 0; i < 10; i++) {
            double rx = (level.random.nextDouble() - 0.5) * 1.2;
            double ry = (level.random.nextDouble() - 0.2) * 0.6;
            double rz = (level.random.nextDouble() - 0.5) * 1.2;
            level.sendParticles(ParticleTypes.POOF, pos.x, pos.y + 0.2, pos.z, 1, rx, ry, rz, 0.02);
        }


    }

    private static double knockScale(int count) {
        return Mth.clamp(1.15 - 0.15 * Math.sqrt(Math.max(1, count)), 0.85, 1.15);
    }

    // ================= ВИЗУАЛ: три частицы (лево, центр, право) =================
    private static void spawnBladeParticles3(ServerLevel level, Vec3 from, Vec3 to, Vec3 dirNorm, boolean hitWallNow) {
        if (!isFinite(from) || !isFinite(to) || !isFinite(dirNorm)) return;

        // поперечный вектор по XZ
        Vec3 right = new Vec3(-dirNorm.z, 0.0, dirNorm.x);
        double rl = right.length();
        if (rl < 1e-6) right = new Vec3(1, 0, 0);
        else right = right.scale(1.0 / rl);

        Vec3 mid = from.add(to).scale(0.5);
        double y = mid.y + 0.05;

        // три оффсета: левый край, центр, правый край
        double[] offs = new double[] { -LATERAL_HALF, 0.0, LATERAL_HALF };

        for (int i = 0; i < 3; i++) {
            double off = offs[i];
            double jx = (level.random.nextDouble() - 0.5) * PARTICLE_JITTER;
            double jz = (level.random.nextDouble() - 0.5) * PARTICLE_JITTER;
            double jy = (level.random.nextDouble() - 0.5) * PARTICLE_JITTER;
            Vec3 p = mid.add(right.scale(off)).add(jx, jy, jz);

            level.sendParticles(ParticleTypes.SWEEP_ATTACK, p.x, y, p.z, 1, 0, 0, 0, 0.0);

            if (hitWallNow && i == 1) { // чуть дымка в точке удара (только по центру)
                level.sendParticles(ParticleTypes.CLOUD, p.x, y, p.z, 1, 0.0, 0.0, 0.0, 0.02);
            }
        }
    }

    // ================= Геометрия широкой дорожки =================
    private static AABB makeWideLane(Vec3 from, Vec3 to, double lateralHalf, double verticalHalf) {
        if (!isFinite(from) || !isFinite(to)) return null;

        double minX = Math.min(from.x, to.x) - lateralHalf;
        double maxX = Math.max(from.x, to.x) + lateralHalf;
        double minZ = Math.min(from.z, to.z) - lateralHalf;
        double maxZ = Math.max(from.z, to.z) + lateralHalf;

        double baseYMin = Math.min(from.y, to.y) - verticalHalf;
        double baseYMax = Math.max(from.y, to.y) + verticalHalf;

        if (!isFinite(minX, maxX, minZ, maxZ, baseYMin, baseYMax)) return null;
        return new AABB(minX, baseYMin, minZ, maxX, baseYMax, maxZ);
    }

    private static boolean isFinite(Vec3 v) {
        return v != null && Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }
    private static boolean isFinite(double... a) {
        for (double d : a) if (!Double.isFinite(d)) return false;
        return true;
    }

    private static void tryHurtWeapon(Player p, ItemStack weapon, int amount) {
        if (weapon.isEmpty() || amount <= 0) return;
        EquipmentSlot slot = (p.getUsedItemHand() == InteractionHand.OFF_HAND)
                ? EquipmentSlot.OFFHAND
                : EquipmentSlot.MAINHAND;
        weapon.hurtAndBreak(amount, p, slot);
    }

    private static class Blade {
        final UUID ownerUUID;
        Vec3 pos;
        final Vec3 dir;
        final float damage;
        final boolean canDealDamage;
        final Set<UUID> hitOnce = new HashSet<>();
        double travelled = 0;
        int ticksLived = 0;

        Blade(UUID ownerUUID, Vec3 pos, Vec3 dir, float damage, boolean canDealDamage) {
            this.ownerUUID = ownerUUID;
            this.pos = pos;
            this.dir = dir;
            this.damage = damage;
            this.canDealDamage = canDealDamage;
        }

        Player getOwner(ServerLevel level) {
            Entity e = level.getEntity(ownerUUID);
            return (e instanceof Player p) ? p : null;
        }
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(WindbladeEvents::onAttackEntity);
        NeoForge.EVENT_BUS.addListener(WindbladeEvents::onLeftClickBlock);
        NeoForge.EVENT_BUS.addListener(WindbladeEvents::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(WindbladeEvents::onLevelTick);
    }
}
