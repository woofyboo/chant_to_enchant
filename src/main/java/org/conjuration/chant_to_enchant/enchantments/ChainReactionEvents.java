package org.conjuration.chant_to_enchant.enchantments;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

import static org.conjuration.chant_to_enchant.Chant_to_enchant.MODID;

@EventBusSubscriber(modid = MODID)
public class ChainReactionEvents {
    private static final String CHAIN_ID       = MODID + ":chain_reaction";
    private static final String NBT_CHANCE     = "cte_chain_chance";
    private static final String NBT_LEVEL      = "cte_chain_level";
    private static final String NBT_LAST_HIT   = "cte_last_hit_uuid"; // String UUID
    private static final String NBT_HIT_LIST   = "cte_hit_uuids";      // List<String UUID>
    private static final String NBT_TARGET     = "cte_target_uuid";    // текущая цель (самонаводка)

    // Физика/поиск
    private static final double GRAVITY        = 0.05;
    private static final double DRAG_AIR       = 0.99;
    private static final int    MAX_TICKS_SIM  = 40;
    private static final double HIT_INFLATE    = 0.3;
    private static final double SCAN_RADIUS    = 16.0;

    // Самонаводка
    private static final int    HOMING_MAX_TICKS      = 600; // 30 сек
    private static final int    HOMING_REACQUIRE_EVERY= 5;   // каждые N тиков пробуем найти цель, если нет
    private static final double HOMING_BLEND          = 0.35; // насколько сильно поворачиваем к цели за тик (0..1)
    private static final double HOMING_MIN_SPEED      = 0.55; // нижняя граница скорости
    private static final double HOMING_MAX_SPEED      = 1.15; // верхняя граница скорости

    // Снижение шанса по уровням (1..5): 25/20/15/10/5%
    private static double chanceDecayForLevel(int lvl) {
        return switch (Math.max(1, Math.min(5, lvl))) {
            case 1 -> 0.25;
            case 2 -> 0.20;
            case 3 -> 0.15;
            case 4 -> 0.05;
            case 5 -> 0.01;
            default -> 0.25;
        };
    }

    @SubscribeEvent
    public static void onProjectileImpact(ProjectileImpactEvent event) {
        if (!(event.getProjectile() instanceof AbstractArrow arrow)) return;
        if (!(arrow.level() instanceof ServerLevel level)) return;

        HitResult res = event.getRayTraceResult();

        // если наш рикошет врезался в блок — позволяем подобрать и выходим
        if (res instanceof BlockHitResult && arrow instanceof ChainReactionEvents.Arrow) {
            ((ChainReactionEvents.Arrow) arrow).pickup = AbstractArrow.Pickup.ALLOWED;
            return;
        }

        if (!(res instanceof EntityHitResult ehr)) return;
        Entity hit = ehr.getEntity();
        if (!(hit instanceof LivingEntity victim)) return;

        Entity owner = arrow.getOwner();
        if (!(owner instanceof LivingEntity shooter)) return;

        int chainLevel = getChainLevelFromShooterBow(shooter);
        if (chainLevel <= 0) return;

        CompoundTag tag = arrow.getPersistentData();
        double chance = tag.contains(NBT_CHANCE) ? tag.getDouble(NBT_CHANCE) : 1.0;
        int storedLvl = tag.contains(NBT_LEVEL) ? tag.getInt(NBT_LEVEL) : chainLevel;
        if (!tag.contains(NBT_LEVEL)) tag.putInt(NBT_LEVEL, storedLvl);

        // уже битыe + последний, чтобы не было пинг-понга
        Set<UUID> alreadyHit = readHitSet(tag);
        alreadyHit.add(victim.getUUID());
        UUID last = tag.contains(NBT_LAST_HIT, Tag.TAG_STRING) ? uuidFromString(tag.getString(NBT_LAST_HIT)) : null;

        // шанс провален — цепочка прекращается, делаем стрелу подбираемой
        if (ThreadLocalRandom.current().nextDouble() > chance) {
            if (arrow instanceof ChainReactionEvents.Arrow ric) {
                ric.pickup = AbstractArrow.Pickup.ALLOWED;
            }
            return;
        }

        // вычисляем точку спауна рикошета (не из ног)
        Vec3 impact = ehr.getLocation();
        double chestY = victim.getY() + victim.getBbHeight() * 0.4;
        double spawnY = Math.max(impact.y, chestY);
        Vec3 fromVictim = new Vec3(arrow.getX(), spawnY, arrow.getZ());
        Vec3 outward = chestPoint(victim).subtract(fromVictim).normalize().scale(0.12);
        Vec3 spawnPos = fromVictim.add(outward).add(0, 0.02, 0);

        // Кандидаты
        List<LivingEntity> candidates = gatherCandidates(level, victim, shooter, SCAN_RADIUS, alreadyHit, last);

        // скорость следующего полёта
        double baseSpeed = arrow.getDeltaMovement().length();
        double ricSpeed  = clamp(baseSpeed * 0.65, HOMING_MIN_SPEED, HOMING_MAX_SPEED);
        double ricSpeed2 = Math.min(1.25, ricSpeed * 1.15);

        ShotSolution sol = pickBestShot(level, spawnPos, candidates, ricSpeed, ricSpeed2);

        // следующая вероятность
        double decay      = chanceDecayForLevel(storedLvl);
        double nextChance = Math.max(0.0, chance - decay);

        // если нашли цель — задаём самонаводку, иначе летим «умно» в свободное направление
        Vec3 initialVelocity;
        UUID targetUUID = null;

        if (sol != null) {
            initialVelocity = sol.velocity;
            targetUUID = sol.target.getUUID(); // **самонаводка на этого**
        } else {
            initialVelocity = pickFreePathVelocity(level, spawnPos, ricSpeed);
        }

        spawnRicochetArrow(level, shooter, spawnPos, initialVelocity, arrow, nextChance, storedLvl,
                victim.getUUID(), alreadyHit, targetUUID);

        // исходную удаляем (без дюпа)
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
        level.getServer().execute(arrow::discard);
    }

    // ==== Поиск цели и траектории ==============================================================

    private static List<LivingEntity> gatherCandidates(ServerLevel level,
                                                       LivingEntity from,
                                                       LivingEntity shooter,
                                                       double radius,
                                                       Set<UUID> alreadyHit,
                                                       UUID lastHit) {
        Predicate<LivingEntity> valid = e ->
                e.isAlive()
                        && e != from
                        && e != shooter
                        && !isShootersPet(e, shooter)
                        && !alreadyHit.contains(e.getUUID())
                        && (lastHit == null || !e.getUUID().equals(lastHit))
                        && e.isPickable()
                        && e.isAttackable();

        AABB box = from.getBoundingBox().inflate(radius);
        List<LivingEntity> list = level.getEntitiesOfClass(LivingEntity.class, box, valid);
        list.sort(Comparator.comparingDouble(e -> e.distanceToSqr(from)));
        return list;
    }

    private static ShotSolution pickBestShot(ServerLevel level,
                                             Vec3 spawnPos,
                                             List<LivingEntity> candidates,
                                             double speed1,
                                             double speed2) {
        if (candidates.isEmpty()) return null;

        for (LivingEntity target : candidates) {
            Vec3 targetAim = chestPoint(target);

            for (double speed : new double[]{speed1, speed2}) {
                Vec3 to = targetAim.subtract(spawnPos);
                double horiz = Math.sqrt(to.x * to.x + to.z * to.z);

                double[] boosts = new double[]{ -0.06, -0.03, 0.0, 0.02, 0.04, 0.06, 0.09, 0.12 };
                for (double k : boosts) {
                    double boost = k * (0.5 + 0.5 * Math.min(1.0, horiz / 12.0));
                    Vec3 dir = new Vec3(to.x, to.y + boost * horiz, to.z).normalize();
                    dir = addSmallSpread(dir, 0.0025);
                    Vec3 vel = dir.scale(speed);

                    if (simulateHit(level, spawnPos, vel, target)) {
                        return new ShotSolution(target, vel);
                    }
                }
            }
        }
        return null;
    }

    private static boolean simulateHit(ServerLevel level, Vec3 startPos, Vec3 initVel, LivingEntity target) {
        Vec3 pos = startPos;
        Vec3 vel = initVel;
        AABB targetBox = target.getBoundingBox().inflate(HIT_INFLATE);

        for (int t = 0; t < MAX_TICKS_SIM; t++) {
            Vec3 nextVel = new Vec3(vel.x, vel.y - GRAVITY, vel.z).scale(DRAG_AIR);
            Vec3 nextPos = pos.add(nextVel);

            HitResult blockRes = level.clip(new ClipContext(
                    pos, nextPos, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()
            ));
            if (blockRes.getType() != HitResult.Type.MISS) {
                return false;
            }

            AABB sweep = new AABB(pos, nextPos).inflate(0.1);
            if (sweep.intersects(targetBox)) {
                if (segmentIntersectsAABB(pos, nextPos, targetBox)) {
                    return true;
                }
            }

            pos = nextPos;
            vel = nextVel;
        }
        return false;
    }

    private static boolean segmentIntersectsAABB(Vec3 a, Vec3 b, AABB box) {
        double tmin = 0.0;
        double tmax = 1.0;

        double[] ad = {a.x, a.y, a.z};
        double[] bd = {b.x, b.y, b.z};
        double[] d  = {bd[0] - ad[0], bd[1] - ad[1], bd[2] - ad[2]};
        double[] min = {box.minX, box.minY, box.minZ};
        double[] max = {box.maxX, box.maxY, box.maxZ};

        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1e-7) {
                if (ad[i] < min[i] || ad[i] > max[i]) return false;
            } else {
                double ood = 1.0 / d[i];
                double t1 = (min[i] - ad[i]) * ood;
                double t2 = (max[i] - ad[i]) * ood;
                if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
                tmin = Math.max(tmin, t1);
                tmax = Math.min(tmax, t2);
                if (tmin > tmax) return false;
            }
        }
        return true;
    }

    private static Vec3 pickFreePathVelocity(ServerLevel level, Vec3 startPos, double baseSpeed) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Vec3 bestVel = null;
        double bestDist = -1;

        for (int i = 0; i < 24; i++) {
            Vec3 dir = randomDirSlightUp();
            Vec3 vel = dir.scale(baseSpeed);
            double dist = simulateFreeDistance(level, startPos, vel);
            if (dist > bestDist) {
                bestDist = dist;
                bestVel = vel;
            }
        }
        return bestVel != null ? bestVel : randomDirSlightUp().scale(baseSpeed);
    }

    private static double simulateFreeDistance(ServerLevel level, Vec3 startPos, Vec3 initVel) {
        Vec3 pos = startPos;
        Vec3 vel = initVel;
        double traveled = 0.0;

        for (int t = 0; t < MAX_TICKS_SIM; t++) {
            Vec3 nextVel = new Vec3(vel.x, vel.y - GRAVITY, vel.z).scale(DRAG_AIR);
            Vec3 nextPos = pos.add(nextVel);

            HitResult blockRes = level.clip(new ClipContext(
                    pos, nextPos, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()
            ));
            if (blockRes.getType() != HitResult.Type.MISS) {
                traveled += pos.distanceTo(blockRes.getLocation());
                return traveled;
            }

            traveled += pos.distanceTo(nextPos);
            pos = nextPos;
            vel = nextVel;
        }
        return traveled;
    }

    // ==== Вспомогалки ===========================================================================

    private static int getChainLevelFromShooterBow(LivingEntity shooter) {
        ItemStack main = shooter.getMainHandItem();
        ItemStack off  = shooter.getOffhandItem();

        ItemStack bowStack = ItemStack.EMPTY;
        if (main.getItem() instanceof BowItem) bowStack = main;
        else if (off.getItem() instanceof BowItem) bowStack = off;
        if (bowStack.isEmpty()) return 0;

        return getEnchantLevel(bowStack, CHAIN_ID);
    }

    private static int getEnchantLevel(ItemStack stack, String enchantmentId) {
        ItemEnchantments ench = stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        for (var entry : ench.entrySet()) {
            Holder<?> holder = entry.getKey();
            if (holder.unwrapKey().isPresent()
                    && holder.unwrapKey().get().location().toString().equals(enchantmentId)) {
                return entry.getIntValue();
            }
        }
        return 0;
    }

    private static boolean isShootersPet(Entity e, LivingEntity shooter) {
        if (e instanceof OwnableEntity own) {
            Entity owner = own.getOwner();
            if (owner != null && owner.is(shooter)) return true;
        }
        if (e instanceof AbstractHorse horse && horse.isTamed()) {
            if (horse.getOwnerUUID() != null && shooter.getUUID().equals(horse.getOwnerUUID())) return true;
        }
        return false;
    }

    private static Vec3 chestPoint(LivingEntity target) {
        double y = target.getY() + target.getBbHeight() * 0.4;
        return new Vec3(target.getX(), y, target.getZ());
    }

    private static Vec3 addSmallSpread(Vec3 dir, double spread) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double dx = dir.x + (r.nextDouble() * 2 - 1) * spread;
        double dy = dir.y + (r.nextDouble() * 2 - 1) * spread * 0.5;
        double dz = dir.z + (r.nextDouble() * 2 - 1) * spread;
        return new Vec3(dx, dy, dz).normalize();
    }

    private static Vec3 randomDirSlightUp() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double u = r.nextDouble();
        double v = r.nextDouble();
        double theta = 2 * Math.PI * u;
        double phi = Math.acos(2 * v - 1);
        double x = Math.sin(phi) * Math.cos(theta);
        double y = Math.sin(phi) * Math.sin(theta);
        double z = Math.cos(phi);
        Vec3 v3 = new Vec3(x, y, z);
        if (v3.y < 0.05) v3 = new Vec3(v3.x, 0.05, v3.z).normalize();
        return v3;
    }

    private static void spawnRicochetArrow(ServerLevel level,
                                           LivingEntity shooter,
                                           Vec3 pos,
                                           Vec3 velocity,
                                           AbstractArrow sourceArrow,
                                           double nextChance,
                                           int chainLevel,
                                           UUID currentVictim,
                                           Set<UUID> alreadyHit,
                                           UUID targetUUID) {
        Arrow ric = new Arrow(level);
        ric.setOwner(shooter);
        ric.setPos(pos.x, pos.y, pos.z);

        // Наследуем важное
        ric.setCritArrow(sourceArrow.isCritArrow());
        ric.setNoGravity(false); // гравитацию выключим в тике если будет цель
        ric.setSilent(sourceArrow.isSilent());
        ric.setRemainingFireTicks(sourceArrow.getRemainingFireTicks());

        // Пока цепочка жива — нельзя подобрать
        ric.pickup = AbstractArrow.Pickup.DISALLOWED;

        // Полёт
        ric.setDeltaMovement(velocity);
        ric.hasImpulse = true;
        alignToVelocity(ric, velocity);

        // Пробрасываем состояние
        CompoundTag t = ric.getPersistentData();
        t.putDouble(NBT_CHANCE, nextChance);
        t.putInt(NBT_LEVEL, chainLevel);
        t.putString(NBT_LAST_HIT, currentVictim.toString());
        writeHitSet(t, alreadyHit);

        if (targetUUID != null) {
            t.putString(NBT_TARGET, targetUUID.toString());
            ric.setNoGravity(true); // при самонаводке — без гравитации, чтобы точно попасть
        }

        level.addFreshEntity(ric);
    }

    private static void alignToVelocity(Entity e, Vec3 vel) {
        Vec3 n = vel.normalize();
        float yaw = (float) (Mth.atan2(n.z, n.x) * (180.0 / Math.PI)) - 90.0f;
        float pitch = (float) (-(Mth.atan2(n.y, Math.sqrt(n.x * n.x + n.z * n.z)) * (180.0 / Math.PI)));
        e.setYRot(yaw);
        e.setXRot(pitch);
        e.yRotO = yaw;
        e.xRotO = pitch;
        e.setOldPosAndRot();
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private record ShotSolution(LivingEntity target, Vec3 velocity) { }

    // ==== NBT-хелперы ===========================================================================

    private static Set<UUID> readHitSet(CompoundTag tag) {
        Set<UUID> set = new HashSet<>();
        if (tag.contains(NBT_HIT_LIST, Tag.TAG_LIST)) {
            ListTag list = tag.getList(NBT_HIT_LIST, Tag.TAG_STRING);
            for (Tag t : list) {
                String s = t.getAsString();
                try { set.add(UUID.fromString(s)); } catch (Exception ignored) {}
            }
        }
        return set;
    }

    private static void writeHitSet(CompoundTag tag, Set<UUID> set) {
        ListTag list = new ListTag();
        for (UUID u : set) {
            list.add(StringTag.valueOf(u.toString()));
        }
        tag.put(NBT_HIT_LIST, list);
    }

    private static UUID uuidFromString(String s) {
        try { return UUID.fromString(s); } catch (Exception e) { return null; }
    }

    // ==== Кастомная стрела с самонаводкой =======================================================

    public static class Arrow extends AbstractArrow {
        public Arrow(Level level) {
            super(EntityType.ARROW, level);
            this.setBaseDamage(2.0D);
        }
        public Arrow(EntityType<? extends AbstractArrow> type, Level level) {
            super(type, level);
        }

        @Override
        public void tick() {
            super.tick();
            if (this.level().isClientSide) return;

            // авто-деспавн через 30 сек
            if (this.tickCount > HOMING_MAX_TICKS) {
                this.discard();
                return;
            }

            // если в земле — можно подбирать и дальше ничего не делаем
            if (this.inGround) {
                this.pickup = Pickup.ALLOWED;
                return;
            }

            CompoundTag t = this.getPersistentData();

            // пробуем найти цель из NBT
            LivingEntity target = null;
            if (t.contains(NBT_TARGET, Tag.TAG_STRING)) {
                UUID tu = uuidFromString(t.getString(NBT_TARGET));
                if (tu != null && this.level() instanceof ServerLevel sl) {
                    target = (LivingEntity) sl.getEntity(tu);
                    if (target != null && (!target.isAlive() || target.isRemoved())) {
                        target = null;
                    }
                }
            }

            // если цели нет — иногда ре-аккуирим новую (если наводки изначально не было)
            if (target == null && this.tickCount % HOMING_REACQUIRE_EVERY == 0 && this.level() instanceof ServerLevel sl2) {
                // достаём данные для фильтра (кто стрелял, кого уже били)
                Entity owner = this.getOwner();
                LivingEntity shooter = owner instanceof LivingEntity le ? le : null;

                Set<UUID> already = readHitSet(t);
                UUID last = t.contains(NBT_LAST_HIT, Tag.TAG_STRING) ? uuidFromString(t.getString(NBT_LAST_HIT)) : null;

                // Собираем кандидатов вокруг текущей позиции стрелы
                // (делаем фиктивную "жертву" с AABB на месте стрелы, чтобы переиспользовать gatherCandidates)
                LivingEntity fake = null; // from = null => просто не исключим «себя»
                List<LivingEntity> cands = new ArrayList<>();
                {
                    Predicate<LivingEntity> valid = e ->
                            e.isAlive()
                                    && (shooter == null || (e != shooter && !isShootersPet(e, shooter)))
                                    && !already.contains(e.getUUID())
                                    && (last == null || !e.getUUID().equals(last))
                                    && e.isPickable()
                                    && e.isAttackable();

                    AABB box = this.getBoundingBox().inflate(SCAN_RADIUS);
                    cands = sl2.getEntitiesOfClass(LivingEntity.class, box, valid);
                    cands.sort(Comparator.comparingDouble(e -> e.distanceToSqr(this)));
                }

                // выбираем ближайшего достижимого и ставим наводку
                for (LivingEntity cand : cands) {
                    Vec3 aim = chestPoint(cand);
                    Vec3 to  = aim.subtract(this.position());
                    Vec3 dir = to.normalize();
                    Vec3 testVel = dir.scale(Math.max(HOMING_MIN_SPEED, Math.min(HOMING_MAX_SPEED, this.getDeltaMovement().length())));
                    if (simulateHit(sl2, this.position(), testVel, cand)) {
                        target = cand;
                        t.putString(NBT_TARGET, cand.getUUID().toString());
                        this.setNoGravity(true);
                        break;
                    }
                }
            }

            if (target != null) {
                // идеальная самонаводка: поворачиваем курс на цель каждый тик
                Vec3 aimPoint = chestPoint(target);
                Vec3 to = aimPoint.subtract(this.position());
                Vec3 desiredDir = to.normalize();

                Vec3 curVel = this.getDeltaMovement();
                double speed = curVel.length();
                if (speed < HOMING_MIN_SPEED) speed = HOMING_MIN_SPEED;
                if (speed > HOMING_MAX_SPEED) speed = HOMING_MAX_SPEED;

                Vec3 curDir = speed > 1e-6 ? curVel.normalize() : desiredDir;
                Vec3 newDir = curDir.scale(1.0 - HOMING_BLEND).add(desiredDir.scale(HOMING_BLEND)).normalize();

                Vec3 newVel = newDir.scale(speed);
                this.setDeltaMovement(newVel);
                this.hasImpulse = true;
                alignToVelocity(this, newVel);

                // без гравитации, чтобы не «роняло» траекторию
                if (!this.isNoGravity()) this.setNoGravity(true);
            }
        }

        @Override
        protected ItemStack getDefaultPickupItem() {
            return new ItemStack(Items.ARROW);
        }
    }
}
