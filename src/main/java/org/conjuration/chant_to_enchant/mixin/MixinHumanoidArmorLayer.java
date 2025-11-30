package org.conjuration.chant_to_enchant.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import org.conjuration.chant_to_enchant.enchantments.ModEnchantments;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HumanoidArmorLayer.class)
public abstract class MixinHumanoidArmorLayer {

    @Inject(
            method = "render",
            at = @At("HEAD"),
            cancellable = true
    )
    private void chant_to_enchant$hideArmorIfStealthed(
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int packedLight,
            LivingEntity entity,
            float limbSwing,
            float limbSwingAmount,
            float partialTicks,
            float ageInTicks,
            float netHeadYaw,
            float headPitch,
            CallbackInfo ci
    ) {
        // если не невидим — броню рисуем как обычно
        if (!entity.isInvisible()) {
            return;
        }

        if (!(entity.level() instanceof ClientLevel clientLevel)) {
            return;
        }

        // наш Stealth энчант из реестра
        HolderLookup.RegistryLookup<Enchantment> enchRegistry =
                clientLevel.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        ResourceKey<Enchantment> key = ModEnchantments.STEALTH;
        Holder<Enchantment> stealthHolder = enchRegistry.getOrThrow(key);

        // проверяем штаны
        ItemStack legs = entity.getItemBySlot(EquipmentSlot.LEGS);
        if (legs.isEmpty()) {
            return;
        }

        int lvl = EnchantmentHelper.getItemEnchantmentLevel(stealthHolder, legs);
        if (lvl <= 0) {
            // инвиз, но не наш стелс → оставляем броню
            return;
        }

        // наш стелс + инвиз → отменяем рендер брони
        ci.cancel();
    }
}
