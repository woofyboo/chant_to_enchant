package org.conjuration.chant_to_enchant.enchantments;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent;
import org.conjuration.chant_to_enchant.Chant_to_enchant;

@EventBusSubscriber(modid = Chant_to_enchant.MODID)
public class StepEvents {

    // ID модификатора, чисто наш
    private static final ResourceLocation STEP_HEIGHT_MODIFIER_ID =
            ResourceLocation.fromNamespaceAndPath(Chant_to_enchant.MODID, "step_height_bonus");

    // сколько высоты шага даёт 1 уровень зачара
    private static final double STEP_PER_LEVEL = 0.5D;

    // --- ивенты ---

    @SubscribeEvent
    public static void onEquipmentChange(LivingEquipmentChangeEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide()) return;

        if (event.getSlot() == EquipmentSlot.LEGS) {
            recalcStepHeight(entity);
        }
    }

    @SubscribeEvent
    public static void onEntityJoinWorld(EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof LivingEntity living)) return;
        if (living.level().isClientSide()) return;

        recalcStepHeight(living);
    }

    // --- логика перерасчёта ---

    private static void recalcStepHeight(LivingEntity entity) {
        AttributeInstance attr = entity.getAttribute(Attributes.STEP_HEIGHT);
        if (attr == null) return;

        // убираем старый наш модификатор
        attr.removeModifier(STEP_HEIGHT_MODIFIER_ID);

        int level = getStepEnchantLevel(entity);
        if (level <= 0) {
            // зачара нет – ничего не добавляем
            return;
        }

        double bonus = level * STEP_PER_LEVEL;

        AttributeModifier modifier = new AttributeModifier(
                STEP_HEIGHT_MODIFIER_ID,
                bonus,
                AttributeModifier.Operation.ADD_VALUE
        );

        // временный модификатор (живёт пока сущность в мире)
        attr.addTransientModifier(modifier);
    }

    private static int getStepEnchantLevel(LivingEntity entity) {
        ItemStack leggings = entity.getItemBySlot(EquipmentSlot.LEGS);
        if (leggings.isEmpty()) return 0;

        // достаём Holder<Enchantment> для нашего зачара step из реестра
        Registry<Enchantment> registry =
                entity.level().registryAccess().registryOrThrow(Registries.ENCHANTMENT);

        Holder<Enchantment> stepHolder = registry.getHolderOrThrow(ModEnchantments.STEP);

        return EnchantmentHelper.getItemEnchantmentLevel(stepHolder, leggings);
    }
}
