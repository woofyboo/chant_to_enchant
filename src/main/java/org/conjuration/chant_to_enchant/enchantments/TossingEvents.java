package org.conjuration.chant_to_enchant.enchantments;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import org.conjuration.chant_to_enchant.Chant_to_enchant;

@EventBusSubscriber(modid = Chant_to_enchant.MODID)
public class TossingEvents {

    private static final ResourceKey<Enchantment> TOSSING_KEY =
            ResourceKey.create(
                    Registries.ENCHANTMENT,
                    ResourceLocation.fromNamespaceAndPath(Chant_to_enchant.MODID, "tossing")
            );

    private static Holder<Enchantment> TOSSING_ENCHANT;

    private static final String NBT_TOSS_TICKS = "chant_to_enchant_tossing_ticks";
    private static final String NBT_TOSS_POWER = "chant_to_enchant_tossing_power";

    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        LivingEntity target = event.getEntity();
        DamageSource source = event.getSource();
        Entity direct = source.getEntity();

        if (!(direct instanceof LivingEntity attacker)) return;
        if (attacker == target) return;

        ItemStack weapon = attacker.getMainHandItem();
        if (weapon.isEmpty()) return;

        Level level = attacker.level();
        Holder<Enchantment> enchantmentHolder = getTossingEnchant(level);
        if (enchantmentHolder == null) return;

        int enchantLevel = EnchantmentHelper.getTagEnchantmentLevel(enchantmentHolder, weapon);
        if (enchantLevel <= 0) return;

        if (event.getAmount() <= 0.0F) return;

        if (attacker instanceof Player player) {
            float strength = player.getAttackStrengthScale(0.0F);
            if (strength < 0.9F) return;
        }

        // новые, "средние" значения
        int ticks;
        double powerPerTick;
        double instantBoost;

        switch (enchantLevel) {
            case 1 -> {
                instantBoost = 0.45D;
                ticks = 3;
                powerPerTick = 0.115D;
            }
            default -> {
                instantBoost = 0.45D;
                ticks = 4;
                powerPerTick = 0.115D;
            }
        }

        Vec3 motion = target.getDeltaMovement();
        double newY = Math.max(motion.y, instantBoost);
        target.setDeltaMovement(motion.x, newY, motion.z);

        CompoundTag data = target.getPersistentData();
        data.putInt(NBT_TOSS_TICKS, ticks);
        data.putDouble(NBT_TOSS_POWER, powerPerTick);
    }

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof LivingEntity living)) return;

        CompoundTag data = living.getPersistentData();
        int ticks = data.getInt(NBT_TOSS_TICKS);
        if (ticks <= 0) return;

        double power = data.getDouble(NBT_TOSS_POWER);
        if (power <= 0) power = 0.115D;

        Vec3 motion = living.getDeltaMovement();
        living.setDeltaMovement(motion.x, motion.y + power, motion.z);

        ticks--;
        if (ticks <= 0) {
            data.remove(NBT_TOSS_TICKS);
            data.remove(NBT_TOSS_POWER);
        } else {
            data.putInt(NBT_TOSS_TICKS, ticks);
        }
    }

    private static Holder<Enchantment> getTossingEnchant(Level level) {
        if (TOSSING_ENCHANT == null && level instanceof ServerLevel serverLevel) {
            Registry<Enchantment> registry =
                    serverLevel.registryAccess().registryOrThrow(Registries.ENCHANTMENT);
            TOSSING_ENCHANT =
                    registry.getHolder(TOSSING_KEY).orElse(null);
        }
        return TOSSING_ENCHANT;
    }
}
