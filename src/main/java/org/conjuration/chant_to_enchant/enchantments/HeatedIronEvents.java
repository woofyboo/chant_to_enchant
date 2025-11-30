package org.conjuration.chant_to_enchant.enchantments;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingShieldBlockEvent;

import java.util.Map;

import static org.conjuration.chant_to_enchant.Chant_to_enchant.MODID;

public class HeatedIronEvents {

    private static final ResourceLocation HEATED_IRON_ID =
            ResourceLocation.fromNamespaceAndPath(MODID, "heated_iron");

    @SubscribeEvent
    public static void onShieldBlock(LivingShieldBlockEvent event) {
        LivingEntity defender = event.getEntity();
        if (defender == null) return;

        int level = getHeatedIronLevel(defender);
        if (level <= 0) return;

        DamageSource source = event.getDamageSource();
        if (source == null) return;

        // Если прилетел снаряд — просто поджигаем снаряд
        Entity direct = source.getDirectEntity();
        if (direct instanceof Projectile projectile) {
            projectile.setRemainingFireTicks(6 * 20); // 6 сек
            return;
        }

        // Иначе — поджигаем атакующего (ближний бой и прочие прямые источники)
        Entity attacker = source.getEntity();
        if (attacker instanceof LivingEntity livingAttacker) {
            int seconds = (level >= 2) ? 12 : 6;
            livingAttacker.setRemainingFireTicks(seconds * 20);
        }
    }

    // ==== utils ====

    private static int getHeatedIronLevel(LivingEntity entity) {
        ItemStack main = entity.getMainHandItem();
        ItemStack off  = entity.getOffhandItem();
        return Math.max(getLevelFromShield(main), getLevelFromShield(off));
    }

    private static int getLevelFromShield(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof ShieldItem)) return 0;

        for (Map.Entry<net.minecraft.core.Holder<Enchantment>, Integer> e
                : EnchantmentHelper.getEnchantmentsForCrafting(stack).entrySet()) {

            boolean isHeatedIron = e.getKey().unwrapKey()
                    .map(k -> k.location().equals(HEATED_IRON_ID))
                    .orElse(false);

            if (isHeatedIron) {
                Integer lvl = e.getValue();
                return lvl == null ? 0 : lvl;
            }
        }
        return 0;
    }
}
