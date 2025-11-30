package org.conjuration.chant_to_enchant.enchantments;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.conjuration.chant_to_enchant.Chant_to_enchant;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class VortexEvents {

    // одна воронка: точка, мир, радиус, сколько тиков осталось, id стрелявшего
    private record VortexInstance(Vec3 pos, ServerLevel level, int radius, int ticksLeft, int ownerId) {}

    private static final List<VortexInstance> ACTIVE = new ArrayList<>();
    // кулдауны по стрелявшим: id сущности -> gameTime последнего вихря
    private static final Map<Integer, Long> COOLDOWN = new HashMap<>();

    @SubscribeEvent
    public static void onProjectileHit(ProjectileImpactEvent event) {
        if (!(event.getProjectile() instanceof AbstractArrow arrow)) return;
        if (!(arrow.level() instanceof ServerLevel level)) return;

        Entity owner = arrow.getOwner();
        if (!(owner instanceof LivingEntity shooter)) return;

        // достаём энчант из реестра
        Holder<Enchantment> vortexEnchant = level.registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(ModEnchantments.VORTEX);

        int lvl = getVortexLevel(shooter, vortexEnchant);
        if (lvl <= 0) return;

        long gameTime = level.getGameTime();
        int shooterId = shooter.getId();

        // кулдаун 8 секунд (160 тиков) на одного носителя
        Long lastCast = COOLDOWN.get(shooterId);
        if (lastCast != null && gameTime - lastCast < 160L) {
            // ещё откат не прошёл — просто выходим, вихрь не создаём
            return;
        }

        // длительность 2/3/4 сек
        int durationTicks = switch (lvl) {
            case 1 -> 40;
            case 2 -> 60;
            case 3 -> 80;
            default -> 40;
        };

        // радиус всасывания 4/6/8
        int radius = switch (lvl) {
            case 1 -> 4;
            case 2 -> 6;
            case 3 -> 8;
            default -> 4;
        };

        // точка — место попадания
        Vec3 vortexPos = event.getRayTraceResult().getLocation();

        ACTIVE.add(new VortexInstance(vortexPos, level, radius, durationTicks, shooterId));
        COOLDOWN.put(shooterId, gameTime);
    }

    // максимум уровня Vortex на любом предмете в руках стрелка
    private static int getVortexLevel(LivingEntity shooter, Holder<Enchantment> vortexEnchant) {
        ItemStack main = shooter.getMainHandItem();
        ItemStack off = shooter.getOffhandItem();

        int lvlMain = EnchantmentHelper.getItemEnchantmentLevel(vortexEnchant, main);
        int lvlOff = EnchantmentHelper.getItemEnchantmentLevel(vortexEnchant, off);

        return Math.max(lvlMain, lvlOff);
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (ACTIVE.isEmpty()) return;

        // лимит скорости, выше которого не даём разгоняться
        final double maxSpeed = 0.55;
        // насколько сильно режем текущую скорость каждый тик (демпфер)
        final double velocityDamping = 0.55;

        long gameTime = level.getGameTime();

        for (int i = 0; i < ACTIVE.size(); i++) {
            VortexInstance vortex = ACTIVE.get(i);

            if (vortex.level() != level) continue;

            int newTicks = vortex.ticksLeft() - 1;
            if (newTicks <= 0) {
                ACTIVE.remove(i);
                i--;
                continue;
            }

            // обновляем таймер
            vortex = new VortexInstance(
                    vortex.pos(),
                    vortex.level(),
                    vortex.radius(),
                    newTicks,
                    vortex.ownerId()
            );
            ACTIVE.set(i, vortex);

            int r = vortex.radius();
            Vec3 center = vortex.pos();
            int ownerId = vortex.ownerId();

            // визуальный вихрь
            spawnVortexParticles(level, center, r, gameTime);

            AABB box = new AABB(
                    center.x - r, center.y - r, center.z - r,
                    center.x + r, center.y + r, center.z + r
            );

            // все LivingEntity, кроме стрелявшего (игроки тоже)
            List<LivingEntity> victims = level.getEntitiesOfClass(
                    LivingEntity.class,
                    box,
                    e -> e.getId() != ownerId
            );

            // целевой радиус орбиты — треть радиуса всасывания (компактный вихрь)
            double targetRadius = r / 3.0;
            if (targetRadius < 1.0) targetRadius = 1.0;

            // целевая высота орбиты — довольно высоко, чтобы меньше биться об стены
            double targetY = center.y + r * 0.6;

            // урон ветром каждые 20 тиков (1 урон)
            boolean doWindDamage = (newTicks % 20 == 0);

            for (LivingEntity mob : victims) {
                Vec3 offset = mob.position().subtract(center);

                // работаем в горизонтали для орбиты
                Vec3 horizontal = new Vec3(offset.x, 0.0, offset.z);
                if (horizontal.lengthSqr() < 1.0E-4) {
                    // если почти в центре — чуть сдвинем, чтобы не было NaN
                    horizontal = new Vec3(1.0, 0.0, 0.0);
                }

                Vec3 radialDir = horizontal.normalize();
                double dist = horizontal.length();

                double radialError = dist - targetRadius;

                // сила, которая держит моба на "кольце" орбиты
                Vec3 radialForce = radialDir.scale(-radialError * 0.12);

                // направление вращения и базовая скорость зависят от id моба и позиции воронки
                int dirSign = ((((mob.getId() * 31)
                        ^ (int) Math.floor(center.x * 16.0)
                        ^ (int) Math.floor(center.z * 16.0)) & 1) == 0) ? 1 : -1;

                Vec3 tangent = new Vec3(-radialDir.z * dirSign, 0.0, radialDir.x * dirSign);

                // базовая скорость вращения, увеличенная на ~50%
                double baseSpeed = (0.16 + (Math.abs(mob.getId()) % 7) * 0.02) * 1.5;
                Vec3 tangentialForce = tangent.scale(baseSpeed);

                // вертикальная сила: тащим к targetY, чтобы поднимать в воздух
                double yError = targetY - mob.getY();
                Vec3 verticalForce = new Vec3(0.0, yError * 0.12, 0.0);

                // суммарная "добавочная" сила
                Vec3 total = radialForce.add(tangentialForce).add(verticalForce);

                // применяем демпфер к текущей скорости,
                // чтобы не накапливалась бесконечная инерция
                Vec3 currentVel = mob.getDeltaMovement().scale(velocityDamping);
                Vec3 newVel = currentVel.add(total);

                // жёсткий лимит скорости — чтобы при окончании эффекта не было "выстрела"
                double lenSq = newVel.lengthSqr();
                if (lenSq > maxSpeed * maxSpeed) {
                    newVel = newVel.normalize().scale(maxSpeed);
                }

                mob.setDeltaMovement(newVel);
                mob.hurtMarked = true;

                // урон от ветра (1 урон = полсердечка)
                if (doWindDamage) {
                    mob.hurt(level.damageSources().magic(), 1.0F);
                }
            }
        }
    }

    private static void spawnVortexParticles(ServerLevel level, Vec3 center, int radius, long gameTime) {
        // высота столба
        double height = radius * 1.8;

        // сколько "колец" по высоте
        int rings = 4 + radius;
        // базовый угол вращения, зависящий от времени мира (ускорен на ~50%)
        double baseAngle = (gameTime * 0.25 * 1.5) % (Math.PI * 2.0);

        for (int i = 0; i < rings; i++) {
            double hFrac = (double) i / (double) (rings - 1); // 0..1
            double y = center.y + hFrac * height;

            // радиус кольца — растёт к верху → норм конус
            double ringRadius = hFrac * radius;
            if (ringRadius <= 0.1) ringRadius = 0.1;

            // сколько точек на кольце
            int points = 6 + radius * 2;

            for (int j = 0; j < points; j++) {
                double angle = baseAngle + (j * (Math.PI * 2.0 / points));

                double px = center.x + Math.cos(angle) * ringRadius;
                double pz = center.z + Math.sin(angle) * ringRadius;

                // основной "ветер" — лёгкая пыль
                level.sendParticles(
                        ParticleTypes.WHITE_ASH,
                        px, y, pz,
                        1,
                        0.0, 0.02, 0.0,
                        0.0
                );

                // изредка — более плотные комки воздуха
                if (level.random.nextFloat() < 0.1F) {
                    level.sendParticles(
                            ParticleTypes.CLOUD,
                            px, y, pz,
                            1,
                            0.0, 0.02, 0.0,
                            0.0
                    );
                }
            }
        }
    }
}
