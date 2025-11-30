package org.conjuration.chant_to_enchant.enchantments;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.conjuration.chant_to_enchant.enchantments.ModEnchantments;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

public class DodgeEvents {

    private static final int MAX_CHARGES = 2;

    // вызывать через NeoForge.EVENT_BUS.addListener(DodgeEvents::onPlayerTick);
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        Level level = player.level();

        if (level.isClientSide) return;

        // зачарование на штанах
        ItemStack leggings = player.getItemBySlot(EquipmentSlot.LEGS);
        if (leggings.isEmpty()) return;

        Registry<Enchantment> enchantmentRegistry =
                level.registryAccess().registryOrThrow(Registries.ENCHANTMENT);
        Holder<Enchantment> dodgeEnchant =
                enchantmentRegistry.getHolderOrThrow(ModEnchantments.DODGE);

        int enchantLevel = EnchantmentHelper.getItemEnchantmentLevel(dodgeEnchant, leggings);
        if (enchantLevel <= 0) return;

        CompoundTag tag = player.getPersistentData();
        if (!tag.contains("dodge_charges")) {
            tag.putInt("dodge_charges", MAX_CHARGES);
        }

        int charges = tag.getInt("dodge_charges");
        long gameTime = level.getGameTime();

        boolean wasSprinting = tag.getBoolean("dodge_prev_sprint");
        boolean isSprinting = player.isSprinting();

        // 🔄 восстановление зарядов
        if (tag.contains("dodge_next_recharge")) {
            long nextRecharge = tag.getLong("dodge_next_recharge");
            if (gameTime >= nextRecharge && charges < MAX_CHARGES) {
                charges++;
                tag.putInt("dodge_charges", charges);

                if (charges < MAX_CHARGES) {
                    tag.putLong("dodge_next_recharge", gameTime + 30); // 1.5 сек при 20 т/с
                } else {
                    tag.remove("dodge_next_recharge");
                }
            }
        }

        // 🎯 старт спринта → расходуем заряд
        if (!wasSprinting && isSprinting && charges > 0) {
            charges--;
            tag.putInt("dodge_charges", charges);

            // неуязвимость 0.75 сек (15 тиков)
            tag.putLong("dodge_invul_until", gameTime + 15);

            // запускаем откат, если ещё не идёт
            if (!tag.contains("dodge_next_recharge")) {
                tag.putLong("dodge_next_recharge", gameTime + 30);
            }

            // частицы
            if (level instanceof ServerLevel serverLevel) {
                serverLevel.sendParticles(
                        ParticleTypes.POOF,
                        player.getX(),
                        player.getY() + 1.0,
                        player.getZ(),
                        20,
                        0.3, 0.4, 0.3,
                        0.02
                );
            }
        }

        tag.putBoolean("dodge_prev_sprint", isSprinting);
    }

    // вызывать через NeoForge.EVENT_BUS.addListener(DodgeEvents::onIncomingDamage);
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        Level level = player.level();
        if (level.isClientSide) return;

        CompoundTag tag = player.getPersistentData();
        if (!tag.contains("dodge_invul_until")) return;

        long until = tag.getLong("dodge_invul_until");
        long time = level.getGameTime();

        if (time <= until) {
            // урон не проходит — уворот сработал
            event.setAmount(0);
            event.setCanceled(true);

            // звук уворота только на сервере
            level.playSound(
                    null, // null = слышат все рядом
                    player.getX(),
                    player.getY(),
                    player.getZ(),
                    SoundEvents.PLAYER_ATTACK_SWEEP,
                    SoundSource.PLAYERS,
                    0.7f,   // громкость
                    1.4f    // питч, чуть повыше для "резкости"
            );
        }
    }

}
