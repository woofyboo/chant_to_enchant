package org.conjuration.chant_to_enchant.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.player.Player;
import org.conjuration.chant_to_enchant.enchantments.StealthEvents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

@Mixin(Brain.class)
public abstract class MixinBrain<E extends LivingEntity> {

    @Inject(
            method = "setMemory",
            at = @At("HEAD"),
            cancellable = true
    )
    private <U> void chant_to_enchant$ignoreStealthedPlayerMemory(
            MemoryModuleType<U> type,
            U value,
            CallbackInfo ci
    ) {
        // интересует только "ближайший видимый игрок"
        if (type != MemoryModuleType.NEAREST_VISIBLE_PLAYER) {
            return;
        }

        if (!(value instanceof Optional<?> opt)) {
            return;
        }

        if (opt.isEmpty()) {
            return;
        }

        Object obj = opt.get();
        if (!(obj instanceof Player player)) {
            return;
        }

        // этот игрок вообще в нашем стелсе?
        if (!StealthEvents.isEntityStealthed(player)) {
            return;
        }

        // если игрок в стелсе — мозг вообще не запоминает его как ближайшего видимого
        @SuppressWarnings("unchecked")
        Brain<E> brain = (Brain<E>) (Object) this;

        brain.eraseMemory(type);
        ci.cancel();
    }
}
