package org.conjuration.chant_to_enchant.enchantments;

import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.conjuration.chant_to_enchant.Chant_to_enchant;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@EventBusSubscriber(modid = Chant_to_enchant.MODID)
public class RipplesEvents {

    // отдельные карты состояния для сервера и клиента
    private static final Map<UUID, Boolean> WAS_IN_WATER_SERVER = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> WAS_IN_WATER_CLIENT = new ConcurrentHashMap<>();

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Pre event) {
        Player player = event.getEntity();
        if (player == null) return;

        boolean isClient = player.level().isClientSide();

        // 1) сначала проверяем общие условия
        if (!player.isFallFlying()) return;
        if (!hasRipples(player)) return;

        // 2) определяем, сработал ли "момент входа в воду" на этой стороне
        boolean bouncedThisTick = handleEnterWaterState(player, isClient);
        if (!bouncedThisTick) return;

        // 3) сервер — отвечает за физику, клиент — за визуал (камера)
        if (!isClient) {
            applyBouncePhysics(player);
        } else {
            applyBounceLook(player);
        }
    }

    /**
     * Отслеживает переход "был не в воде → стал в воде" отдельно для сервера и клиента.
     * Возвращает true только в тик входа в воду.
     */
    private static boolean handleEnterWaterState(Player player, boolean isClient) {
        UUID id = player.getUUID();
        boolean inWater = player.isInWater();

        Map<UUID, Boolean> map = isClient ? WAS_IN_WATER_CLIENT : WAS_IN_WATER_SERVER;
        boolean wasInWater = map.getOrDefault(id, false);

        // обновляем состояние
        map.put(id, inWater);

        // интересен только переход "не был в воде → стал в воде"
        return !wasInWater && inWater;
    }

    /**
     * Серверная часть: физика отскока.
     */
    private static void applyBouncePhysics(Player player) {
        Vec3 motion = player.getDeltaMovement();

        // БАЗОВЫЕ МНОЖИТЕЛИ ТУРБО-ОТСКОКА
        double verticalBoost = 1.4;      // раньше было ~0.9 → усиливаем нормально
        double speedScale    = 2.2;      // множитель того, насколько скорость падения усиливает отскок
        double maxBounce     = 2.4;      // максимальная скорость вверх (чтоб совсем в стратосферу не улетел)
        double horizBoost    = 1.12;     // усиливаем горизонтальный импульс (чуть-чуть)

        // вычисляем вертикальный импульс
        double bounceY = verticalBoost + Math.min(maxBounce, Math.abs(motion.y) * speedScale);

        // создаём новый вектор скорости
        Vec3 newMotion = new Vec3(
                motion.x * horizBoost,  // усиливаем горизонталь
                bounceY,                // мощный вертикальный пинок
                motion.z * horizBoost
        );

        // применяем физику
        player.setDeltaMovement(newMotion);
        player.hurtMarked = true;

        // сразу возвращаем элитровый полёт
        player.startFallFlying();
    }


    /**
     * Клиентская часть: поворачиваем камеру вверх, чтобы визуально чувствовался отскок.
     */
    private static void applyBounceLook(Player player) {
        // текущий pitch — скорее всего что-то типа +20..+60 (вниз)
        float currentPitch = player.getXRot();

        // хотим развернуть голову вверх, но не экстремально
        // например, если смотрел вниз, просто жёстко перекинуть в -20
        float targetPitch = -20.0F;

        player.setXRot(targetPitch);
        player.setYHeadRot(player.getYRot());
    }

    private static boolean hasRipples(Player player) {
        ItemStack chest = player.getInventory().getArmor(2);
        if (chest.isEmpty()) return false;

        RegistryAccess registryAccess = player.level().registryAccess();
        Holder<Enchantment> ripplesHolder = registryAccess
                .lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(ModEnchantments.RIPPLES);

        int level = EnchantmentHelper.getItemEnchantmentLevel(ripplesHolder, chest);
        return level > 0;
    }
}
