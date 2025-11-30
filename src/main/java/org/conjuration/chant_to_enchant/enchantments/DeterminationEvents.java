package org.conjuration.chant_to_enchant.enchantments;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import org.conjuration.chant_to_enchant.Chant_to_enchant;

@EventBusSubscriber(modid = Chant_to_enchant.MODID)
public class DeterminationEvents {

    public static final ResourceKey<Enchantment> DETERMINATION_KEY = ModEnchantments.DETERMINATION;

    private static final ResourceLocation DETERMINATION_MODIFIER_ID =
            ResourceLocation.fromNamespaceAndPath(Chant_to_enchant.MODID, "determination_hp_penalty");

    // ====== 1) ВЗРЫВ + РЕВАЙВ ПРИ СМЕРТЕЛЬНОМ УДАРЕ ======
    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        LivingEntity entity = event.getEntity();
        Level level = entity.level();

        if (level.isClientSide()) return;
        if (!(level instanceof ServerLevel serverLevel)) return;

        // 🔸 Если у сущности есть тотем в руке — даём сработать тотему, а не Решительности
        if (entity.getMainHandItem().is(Items.TOTEM_OF_UNDYING)
                || entity.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) {
            return;
        }

        ItemStack chest = entity.getItemBySlot(EquipmentSlot.CHEST);
        if (chest.isEmpty()) return;

        Holder<Enchantment> determinationHolder = serverLevel.registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(DETERMINATION_KEY);

        int enchantLevel = EnchantmentHelper.getTagEnchantmentLevel(determinationHolder, chest);
        if (enchantLevel <= 0) return;

        float health = entity.getHealth();
        float incoming = event.getAmount();

        // если урон не смертельный – выходим
        if (health - incoming > 0.0F) {
            return;
        }

        // Спасаем носителя
        event.setCanceled(true);
        event.setAmount(0.0F);

        // ===== "Взрыв" — визуал + ручной AoE-урон =====
        double radius = 3.0D + enchantLevel * 1.5D;
        float baseDamage = 12.0F + enchantLevel * 8.0F; // очень больно в центре

        Vec3 pos = entity.position();

        AABB box = new AABB(
                pos.x - radius, pos.y - 1.0D, pos.z - radius,
                pos.x + radius, pos.y + 2.0D, pos.z + radius
        );

        var targets = serverLevel.getEntitiesOfClass(LivingEntity.class, box, e -> e != entity);

        for (LivingEntity target : targets) {
            double distSq = target.distanceToSqr(entity);
            if (distSq > radius * radius) continue;

            double dist = Math.sqrt(distSq);
            double factor = 1.0D - (dist / radius); // 1 в центре, 0 на границе
            float dmg = (float) (baseDamage * factor);

            if (dmg > 0.5F) {
                target.hurt(event.getSource(), dmg);
            }
        }

        // Частицы
        serverLevel.sendParticles(
                ParticleTypes.EXPLOSION,
                entity.getX(), entity.getY() + 1.0D, entity.getZ(),
                20 + enchantLevel * 10,
                0.5D + enchantLevel * 0.25D,
                0.25D,
                0.5D + enchantLevel * 0.25D,
                0.02D
        );

        // Звук
        serverLevel.playSound(
                null,
                entity.getX(), entity.getY(), entity.getZ(),
                SoundEvents.GENERIC_EXPLODE,
                SoundSource.PLAYERS,
                0.8F + 0.3F * enchantLevel,
                0.9F + 0.05F * enchantLevel
        );

        // Хил: I — до половины, II — до фула
        float maxHealth = entity.getMaxHealth();
        float healTo = (enchantLevel >= 2) ? maxHealth : maxHealth / 2.0F;
        entity.setHealth(healTo);

        // Понижаем уровень зачара на нагруднике
        EnchantmentHelper.updateEnchantments(chest, enchants -> {
            int current = enchants.getLevel(determinationHolder);
            if (current <= 1) {
                enchants.set(determinationHolder, 0);
            } else {
                enchants.set(determinationHolder, current - 1);
            }
        });
    }

    // ====== 2) ШТРАФ К МАКС. ХП В ЗАВИСИМОСТИ ОТ УРОВНЯ ЗАЧАРА ======
    @SubscribeEvent
    public static void onEquipmentChange(LivingEquipmentChangeEvent event) {
        LivingEntity entity = event.getEntity();
        if (!(entity.level() instanceof ServerLevel serverLevel)) return;

        if (event.getSlot() != EquipmentSlot.CHEST) return;

        AttributeInstance attr = entity.getAttribute(Attributes.MAX_HEALTH);
        if (attr == null) return;

        // всегда сначала снимаем старый модификатор (если был)
        attr.removeModifier(DETERMINATION_MODIFIER_ID);

        ItemStack newChest = event.getTo();
        if (newChest.isEmpty()) return;

        Holder<Enchantment> det = serverLevel.registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(DETERMINATION_KEY);

        int level = EnchantmentHelper.getTagEnchantmentLevel(det, newChest);
        if (level <= 0) return;

        double amount = -2.0D * level; // -2 HP за уровень

        attr.addPermanentModifier(new AttributeModifier(
                DETERMINATION_MODIFIER_ID,
                amount,
                AttributeModifier.Operation.ADD_VALUE
        ));

        if (entity.getHealth() > entity.getMaxHealth()) {
            entity.setHealth(entity.getMaxHealth());
        }
    }
}
