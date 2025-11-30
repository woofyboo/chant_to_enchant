package org.conjuration.chant_to_enchant.enchantments;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.conjuration.chant_to_enchant.Chant_to_enchant;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@EventBusSubscriber(modid = Chant_to_enchant.MODID)
public class OutrunnerEvents {

    private static final ResourceLocation OUTRUNNER_SPEED_ID =
            ResourceLocation.fromNamespaceAndPath(Chant_to_enchant.MODID, "outrunner_speed");

    private static final Map<UUID, Integer> STACKS = new HashMap<>();
    private static final Map<UUID, Integer> SPRINT_TICKS = new HashMap<>();
    private static final Map<UUID, Vec3> LAST_POS = new HashMap<>();

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        Level level = player.level();
        if (level.isClientSide()) return;
        if (!(level instanceof ServerLevel serverLevel)) return;

        UUID id = player.getUUID();

        // === Читаем энчант ===
        Registry<Enchantment> registry =
                serverLevel.registryAccess().registryOrThrow(Registries.ENCHANTMENT);
        Holder<Enchantment> outrunnerHolder =
                registry.getHolderOrThrow(ModEnchantments.OUTRUNNER);

        ItemStack boots = player.getItemBySlot(EquipmentSlot.FEET);
        int enchLevel = 0;
        if (!boots.isEmpty()) {
            enchLevel = EnchantmentHelper.getItemEnchantmentLevel(outrunnerHolder, boots);
        }

        int stacks = STACKS.getOrDefault(id, 0);
        int sprintTicks = SPRINT_TICKS.getOrDefault(id, 0);

        if (enchLevel <= 0 || !player.isAlive() || player.isSpectator()) {
            clearSpeedModifier(player);
            STACKS.remove(id);
            SPRINT_TICKS.remove(id);
            LAST_POS.remove(id);
            return;
        }

        boolean sprinting = player.isSprinting();

        // === СТАКИ ===
        if (sprinting) {
            sprintTicks++;
            if (sprintTicks >= 20) {
                sprintTicks = 0;
                if (stacks < 8) stacks++;
            }
        } else {
            stacks = 0;
            sprintTicks = 0;
        }

        STACKS.put(id, stacks);
        SPRINT_TICKS.put(id, sprintTicks);

        // === СКОРОСТЬ ===

// множитель от уровня зачара
        double lvlMult = (enchLevel == 2 ? 2.0 : 1.0);

// базовые 5% → умножаем уровнем
        double bonus = 0.05 * lvlMult;

// плюс стаки (5% за стак на l1, 10% на l2)
        bonus += stacks * 0.05 * lvlMult;

        boolean fullStacks = stacks >= 8;

// full-stack бонус тоже удваивается
        if (fullStacks) {
            bonus += 0.10 * lvlMult;
        }

        applySpeedModifier(player, bonus);

// === ГОЛОД ===
// → ускоренное потребление = столько же, сколько бонус от зачара
// (уровень уже учтён в bonus)
        Vec3 current = player.position();
        Vec3 last = LAST_POS.get(id);

        if (last != null) {
            double dx = current.x - last.x;
            double dz = current.z - last.z;
            double dist = Math.sqrt(dx * dx + dz * dz);

            LAST_POS.put(id, current);

            if (sprinting && dist > 0 && fullStacks) {
                // ванильный спринт: 0.1 exhaustion за блок
                float base = 0.1F;

                // extra на уровне учитывается автоматически, т.к. bonus уже включает lvlMult
                float extra = (float)(dist * base * bonus);

                player.causeFoodExhaustion(extra);
            }
        } else {
            LAST_POS.put(id, current);
        }

    }

    private static void applySpeedModifier(Player player, double bonus) {
        AttributeInstance movement = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (movement == null) return;

        movement.removeModifier(OUTRUNNER_SPEED_ID);

        if (bonus <= 0) return;

        AttributeModifier modifier = new AttributeModifier(
                OUTRUNNER_SPEED_ID,
                bonus,
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL
        );

        movement.addOrUpdateTransientModifier(modifier);
    }

    private static void clearSpeedModifier(Player player) {
        AttributeInstance movement = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (movement == null) return;
        movement.removeModifier(OUTRUNNER_SPEED_ID);
    }
}
