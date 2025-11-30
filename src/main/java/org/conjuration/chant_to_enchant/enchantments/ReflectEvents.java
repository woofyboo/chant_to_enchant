package org.conjuration.chant_to_enchant.enchantments;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;

import java.util.Random;

import static org.conjuration.chant_to_enchant.Chant_to_enchant.MODID;

@EventBusSubscriber(modid = MODID)
public class ReflectEvents {
    private static final Random RNG = new Random();

    // IDs твоих чар (ресурсные имена в регистре)
    private static final String REFLECT_ID = MODID + ":reflect";
    private static final String HEATED_IRON_ID = MODID + ":heated_iron";

    @SubscribeEvent
    public static void onProjectileImpact(ProjectileImpactEvent event) {
        Projectile projectile = event.getProjectile();
        if (!(projectile.level() instanceof ServerLevel level)) return;

        HitResult hitRes = event.getRayTraceResult();
        if (!(hitRes instanceof EntityHitResult ehr)) return;

        Entity hit = ehr.getEntity();
        if (!(hit instanceof LivingEntity target)) return;

        // цель должна реально блокировать щитом
        if (!target.isBlocking()) return;

        ItemStack active = target.getUseItem();
        if (active.isEmpty() || !active.is(Items.SHIELD)) return;

        int reflectLevel = getEnchantLevel(active, REFLECT_ID);
        if (reflectLevel <= 0) return;

        // === шанс срабатывания отражения (L1=33%, L2=66%, L3=100%) ===
        double triggerChance = Math.min(1.0, reflectLevel / 3.0);
        if (RNG.nextDouble() >= triggerChance) return; // не прокнуло — даём ванилле обработать попадание

        // отменяем ванильный импакт
        event.setCanceled(true);

        // поджигаем снаряд, если на активном щите есть Heated Iron
        int heatedLevel = getEnchantLevel(active, HEATED_IRON_ID);
        if (heatedLevel > 0) {
            int secs = heatedLevel >= 2 ? 12 : 6;
            projectile.setRemainingFireTicks(secs * 20);
        }

        // доп. износ щита (+1 прочности)
        EquipmentSlot slot = target.getOffhandItem() == active ? EquipmentSlot.OFFHAND : EquipmentSlot.MAINHAND;
        active.hurtAndBreak(1, target, slot);

        // расчёт новой скорости/направления — летим обратно к стрелявшему, если он есть
        Entity shooter = projectile.getOwner();
        double speed = projectile.getDeltaMovement().length();
        if (speed < 0.1) speed = 0.6;

        Vec3 from = target.getEyePosition();
        Vec3 newVel;

        if (shooter != null) {
            Vec3 desired = shooter.getEyePosition().subtract(from).normalize();
            newVel = desired.scale(speed);
        } else {
            newVel = projectile.getDeltaMovement().scale(-1).normalize().scale(speed);
        }

        // переносим снаряд к щиту и задаём новую траекторию
        projectile.setPos(from.x, from.y, from.z);

        if (projectile instanceof AbstractArrow arrow) {
            arrow.setOwner(target);
            arrow.setDeltaMovement(newVel);
            arrow.hasImpulse = true;
            alignToVelocity(arrow, newVel);
        } else {
            projectile.setDeltaMovement(newVel);
            projectile.hasImpulse = true;
            alignToVelocity(projectile, newVel);
        }

        // маленькая неуязвимость от мгновенного ре-хита
        projectile.invulnerableTime = 5;
    }

    // Читаем уровень конкретного зачара на стаке
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

    // Красиво поворачиваем сущность по вектору скорости, чтобы не летела "боком"
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
}
