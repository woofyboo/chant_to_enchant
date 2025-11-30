package org.conjuration.chant_to_enchant.enchantments;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import org.conjuration.chant_to_enchant.Chant_to_enchant;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@EventBusSubscriber(modid = Chant_to_enchant.MODID)
public class RamEvents {

    public static final ResourceKey<Enchantment> RAM_ENCHANT_KEY = ModEnchantments.RAM;

    // Кд по тикам мира, просто запоминаем, когда последний раз таран делали
    private static final Map<UUID, Long> LAST_RAM_TICK = new HashMap<>();
    private static final int RAM_COOLDOWN_TICKS = 20; // 1 сек

    // Порог «мы реально бежим»
    private static final double RUN_SPEED_THRESHOLD = 0.10D;

    @SubscribeEvent
    public static void onStartUseItem(LivingEntityUseItemEvent.Start event) {
        if (!(event.getEntity() instanceof Player player)) return;
        ItemStack stack = event.getItem();

        if (!(stack.getItem() instanceof ShieldItem)) return;
        if (!(player.level() instanceof ServerLevel level)) return;

        Vec3 motion = player.getDeltaMovement();
        double speed = motion.length();
        boolean isRunning = speed > RUN_SPEED_THRESHOLD || player.isSprinting();
        if (!isRunning) return;

        UUID id = player.getUUID();
        long gameTime = level.getGameTime();
        long last = LAST_RAM_TICK.getOrDefault(id, -(long) RAM_COOLDOWN_TICKS); // <-- FIX

        if (gameTime - last < RAM_COOLDOWN_TICKS) {
            return;
        }

        Holder<Enchantment> ramHolder = level.registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(RAM_ENCHANT_KEY);

        int levelRam = EnchantmentHelper.getTagEnchantmentLevel(ramHolder, stack);
        if (levelRam <= 0) return;

        doRamBash(level, player, levelRam);
        LAST_RAM_TICK.put(id, gameTime);
    }


    private static void doRamBash(ServerLevel level, Player player, int enchantLevel) {
        double radius = 3.0D + enchantLevel;
        Vec3 pos = player.position();
        Vec3 look = player.getLookAngle().normalize();

        AABB box = new AABB(
                pos.x - radius, pos.y - 1.0D, pos.z - radius,
                pos.x + radius, pos.y + 2.0D, pos.z + radius
        );

        List<Entity> entities = level.getEntities(player, box, e ->
                e instanceof LivingEntity && !(e instanceof Player)
        );

        double basePower = switch (enchantLevel) {
            case 1 -> 1.0D;
            case 2 -> 1.6D;
            case 3 -> 2.3D;
            default -> 1.0D;
        };

        for (Entity e : entities) {
            Vec3 dir = e.position().subtract(pos).normalize();
            double dot = dir.dot(look);
            if (dot < 0.35D) continue; // только те, кто реально перед щитом

            double power = basePower * dot;
            Vec3 push = new Vec3(
                    dir.x * power,
                    0.25D + enchantLevel * 0.05D,
                    dir.z * power
            );
            e.push(push.x, push.y, push.z);

            if (e instanceof LivingEntity le) {
                le.hurtMarked = true;
            }
        }

        // Взрывные частицы
        level.sendParticles(
                ParticleTypes.EXPLOSION,
                player.getX(), player.getY() + 1.0D, player.getZ(),
                20 + enchantLevel * 10,
                0.5D + enchantLevel * 0.2D,
                0.25D,
                0.5D + enchantLevel * 0.2D,
                0.02D
        );

        // Звук
        level.playSound(
                null,
                player.getX(), player.getY(), player.getZ(),
                SoundEvents.GENERIC_EXPLODE,
                SoundSource.PLAYERS,
                0.7F + 0.3F * enchantLevel,
                0.9F + 0.05F * enchantLevel
        );
    }
}
