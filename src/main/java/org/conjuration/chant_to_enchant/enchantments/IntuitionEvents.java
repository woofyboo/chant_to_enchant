package org.conjuration.chant_to_enchant.enchantments;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import org.conjuration.chant_to_enchant.Chant_to_enchant;
import org.conjuration.chant_to_enchant.enchantments.ModEnchantments;

import java.util.*;

public class IntuitionEvents {

    // игрок -> список сущностей, которые сейчас подсвечены ИМ
    private static final Map<UUID, Set<UUID>> trackedByPlayer = new HashMap<>();

    // сущность -> количество игроков с Intuition, которые требуют, чтобы она светиась
    private static final Map<UUID, Integer> glowRefCount = new HashMap<>();

    // сущность -> её состояние glowing ДО Intuition
    private static final Map<UUID, Boolean> previousGlowState = new HashMap<>();

    private static final double PLAYER_RADIUS = 8.0;
    private static final double AGGRO_SEARCH_RADIUS = 64.0;

    public static void onEntityTick(EntityTickEvent.Pre event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.level().isClientSide()) return;
        if (!(player.level() instanceof ServerLevel level)) return;

        UUID playerId = player.getUUID();

        int intuitionLevel = getIntuitionLevel(player);
        if (intuitionLevel <= 0) {
            clearPlayerGlows(level, playerId);
            return;
        }

        // собираем актуальный список целей
        Set<UUID> newSet = new HashSet<>();

        // 1) мобы, у которых target == этот игрок
        AABB mobBox = player.getBoundingBox().inflate(AGGRO_SEARCH_RADIUS);
        List<Mob> mobs = level.getEntitiesOfClass(
                Mob.class,
                mobBox,
                mob -> mob.getTarget() == player
        );

        for (Mob mob : mobs) {
            addGlowFromPlayer(level, playerId, mob, newSet);
        }

        // 2) игроки в радиусе 8 блоков, не невидимые, не он сам
        AABB playerBox = player.getBoundingBox().inflate(PLAYER_RADIUS);
        List<Player> players = level.getEntitiesOfClass(
                Player.class,
                playerBox,
                p -> p != player && !p.isInvisible()
        );

        for (Player other : players) {
            addGlowFromPlayer(level, playerId, other, newSet);
        }

        // снять подсветку с тех, кто был раньше, но не в newSet
        Set<UUID> oldSet = trackedByPlayer.getOrDefault(playerId, Collections.emptySet());
        for (UUID prev : oldSet) {
            if (!newSet.contains(prev)) {
                LivingEntity le = findLivingByUUID(level, prev);
                removeGlowSource(prev, le);
            }
        }

        trackedByPlayer.put(playerId, newSet);
    }

    private static int getIntuitionLevel(Player player) {
        ItemStack helmet = player.getItemBySlot(EquipmentSlot.HEAD);
        if (helmet.isEmpty()) return 0;

        RegistryAccess access = player.level().registryAccess();
        var reg = access.lookupOrThrow(Registries.ENCHANTMENT);
        var holder = reg.getOrThrow(ModEnchantments.INTUITION);

        return EnchantmentHelper.getItemEnchantmentLevel(holder, helmet);
    }

    private static void addGlowFromPlayer(ServerLevel level, UUID playerId, LivingEntity target, Set<UUID> collector) {
        UUID eid = target.getUUID();
        collector.add(eid);

        // Уже трекался этим игроком? значит glowing уже есть
        Set<UUID> current = trackedByPlayer.getOrDefault(playerId, Collections.emptySet());
        if (current.contains(eid)) return;

        int count = glowRefCount.getOrDefault(eid, 0);

        if (count == 0) {
            // до этого никто Intuition не трогал эту сущность
            previousGlowState.put(eid, target.isCurrentlyGlowing());

            if (!target.isCurrentlyGlowing()) {
                target.setGlowingTag(true);
            }
        }

        glowRefCount.put(eid, count + 1);
    }

    private static void removeGlowSource(UUID entityId, LivingEntity target) {
        int count = glowRefCount.getOrDefault(entityId, 0);

        if (count <= 0) {
            glowRefCount.remove(entityId);
            previousGlowState.remove(entityId);
            return;
        }

        if (count == 1) {
            glowRefCount.remove(entityId);

            Boolean prev = previousGlowState.remove(entityId);
            if (target != null) {
                if (prev != null) {
                    target.setGlowingTag(prev);
                } else {
                    target.setGlowingTag(false);
                }
            }
        } else {
            glowRefCount.put(entityId, count - 1);
        }
    }

    private static void clearPlayerGlows(ServerLevel level, UUID playerId) {
        Set<UUID> old = trackedByPlayer.remove(playerId);
        if (old == null || old.isEmpty()) return;

        for (UUID eid : old) {
            LivingEntity le = findLivingByUUID(level, eid);
            removeGlowSource(eid, le);
        }
    }

    private static LivingEntity findLivingByUUID(ServerLevel level, UUID uuid) {
        Entity e = level.getEntity(uuid);
        return e instanceof LivingEntity liv ? liv : null;
    }
}
