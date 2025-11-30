package org.conjuration.chant_to_enchant.enchantments;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.conjuration.chant_to_enchant.Chant_to_enchant.MODID;

/**
 * Bloodshot I–IV
 *
 * — Пока лук полностью натянут, раз в секунду снимаем 2 HP (но не до смерти).
 * — Сколько HP сняли за эту «сессию натяга», столько ПЛОСКО добавим к урону первой выпущенной стрелы.
 * — Максимум тиков «соса» за один выстрел = УРОВЕНЬ зачара (I=1 тик=2 HP, IV=4 тика=8 HP).
 * — Если HP мало — прекращаем дальше сосать. Бонус сохраняется и применится к ближайшей появившейся стреле.
 */
public class BloodshotEvents {

    private static final ResourceLocation BLOODSHOT_ID = ResourceLocation.fromNamespaceAndPath(MODID, "bloodshot");
    private static final ResourceKey<Enchantment> BLOODSHOT_KEY =
            ResourceKey.create(Registries.ENCHANTMENT, BLOODSHOT_ID);

    private static final int TICKS_PER_STACK = 20;     // 1 секунда при полном натяге
    private static final float HP_COST_PER_STACK = 2.0f;
    private static final int BONUS_TTL_TICKS = 40;     // окно, в которое появится стрела после отпускания

    private static final Map<UUID, HoldState> HOLD_STATE = new HashMap<>();
    private static final Map<UUID, PendingBonus> PENDING_BONUS = new HashMap<>();

    private static class HoldState {
        int ticksIntoFullDraw = 0;
        int stacksDone = 0;       // уже высосано тиков
        float hpSpent = 0f;       // суммарно снято HP за этот натяг
        int maxStacks = 0;        // лимит тиков для текущего уровня зачара
    }

    private static class PendingBonus {
        double flatBonus; // сколько добавить к baseDamage
        long expireAt;
    }

    private static boolean isBow(ItemStack stack) {
        return stack.is(Items.BOW);
    }

    private static int getEnchLevelOn(ItemStack stack, LivingEntity ctx) {
        if (ctx == null) return 0;
        Registry<Enchantment> reg = ctx.level().registryAccess().registryOrThrow(Registries.ENCHANTMENT);
        Holder<Enchantment> holder = reg.getHolder(BLOODSHOT_KEY).orElse(null);
        if (holder == null) return 0;
        return stack.getEnchantmentLevel(holder);
    }

    @SubscribeEvent
    public static void onUseTick(LivingEntityUseItemEvent.Tick event) {
        LivingEntity user = event.getEntity();
        ItemStack using = event.getItem();
        if (!isBow(using)) return;

        int level = getEnchLevelOn(using, user);
        if (level <= 0) {
            // нет зачара — сбрасываем прогресс
            HOLD_STATE.remove(user.getUUID());
            return;
        }

        // сколько тиков уже удерживается предмет
        int usedTicks = using.getUseDuration(user) - event.getDuration();
        boolean fullyDrawn = usedTicks >= 20;
        UUID id = user.getUUID();

        if (!fullyDrawn) {
            HOLD_STATE.remove(id);
            return;
        }

        HoldState st = HOLD_STATE.computeIfAbsent(id, k -> new HoldState());
        // При первом заходе под этот натяг — зафиксировать лимит стаков от уровня
        if (st.maxStacks == 0) st.maxStacks = Math.max(1, level);

        // Если лимит уже достигнут — просто ждём отпускания
        if (st.stacksDone >= st.maxStacks) return;

        st.ticksIntoFullDraw++;

        if (st.ticksIntoFullDraw >= TICKS_PER_STACK) {
            st.ticksIntoFullDraw = 0;

            // ещё можно соснуть?
            if (st.stacksDone < st.maxStacks) {
                float hp = user.getHealth();
                // не даём умереть — оставляем хотя бы 0.5 HP буфер
                if (hp > HP_COST_PER_STACK + 0.5f) {
                    user.hurt(user.damageSources().generic(), HP_COST_PER_STACK);
                    st.stacksDone++;
                    st.hpSpent += HP_COST_PER_STACK;
                } else {
                    // ХП мало — прекращаем дальнейшие тики
                    st.stacksDone = st.maxStacks;
                }
            }
        }
    }

    @SubscribeEvent
    public static void onUseStop(LivingEntityUseItemEvent.Stop event) {
        applyPendingBonusFromHold(event.getEntity(), event.getItem());
    }

    @SubscribeEvent
    public static void onUseFinish(LivingEntityUseItemEvent.Finish event) {
        applyPendingBonusFromHold(event.getEntity(), event.getItem());
    }

    private static void applyPendingBonusFromHold(LivingEntity user, ItemStack using) {
        if (!isBow(using)) return;

        int level = getEnchLevelOn(using, user);
        if (level <= 0) return;

        UUID id = user.getUUID();
        HoldState st = HOLD_STATE.remove(id);
        if (st == null) return;

        // Если что-то успели снять — кладём «плоский» бонус
        if (st.hpSpent > 0.0f) {
            long now = user.level().getGameTime();
            PendingBonus pb = new PendingBonus();
            pb.flatBonus = st.hpSpent; // РОВНО сколько HP потратили — столько добавим к baseDamage
            pb.expireAt = now + BONUS_TTL_TICKS;
            PENDING_BONUS.put(id, pb);
        }
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        Entity e = event.getEntity();
        if (!(e instanceof AbstractArrow arrow)) return;

        Entity owner = arrow.getOwner();
        if (!(owner instanceof LivingEntity shooter)) return;

        UUID id = shooter.getUUID();
        PendingBonus pb = PENDING_BONUS.get(id);
        if (pb == null) return;

        long now = event.getLevel().getGameTime();
        if (now > pb.expireAt) {
            PENDING_BONUS.remove(id);
            return;
        }

        // ПРИМЕНЯЕМ ПЛОСКИЙ БОНУС
        arrow.setBaseDamage(arrow.getBaseDamage() + pb.flatBonus);

        // бонус одноразовый
        PENDING_BONUS.remove(id);
    }

    public static void clearAllFor(LivingEntity user) {
        if (user == null) return;
        UUID id = user.getUUID();
        HOLD_STATE.remove(id);
        PENDING_BONUS.remove(id);
    }
}
