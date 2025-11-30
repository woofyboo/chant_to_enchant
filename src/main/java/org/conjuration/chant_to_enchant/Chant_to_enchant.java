package org.conjuration.chant_to_enchant;

import net.neoforged.fml.common.Mod;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.common.NeoForge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.conjuration.chant_to_enchant.enchantments.*;

// ВНИМАНИЕ: никаких клиентских импортов и регов тут!
@Mod(Chant_to_enchant.MODID)
public class Chant_to_enchant {
    public static final String MODID = "chant_to_enchant";
    public static final Logger LOGGER = LogManager.getLogger(MODID);

    public Chant_to_enchant(IEventBus modEventBus, ModContainer modContainer) {
        // конфиг и всё, как у тебя было...

        NeoForge.EVENT_BUS.addListener(
                org.conjuration.chant_to_enchant.enchantments.HeatedIronEvents::onShieldBlock
        );
        org.conjuration.chant_to_enchant.enchantments.WindbladeEvents.register();

        NeoForge.EVENT_BUS.addListener(BloodshotEvents::onUseTick);
        NeoForge.EVENT_BUS.addListener(BloodshotEvents::onUseStop);
        NeoForge.EVENT_BUS.addListener(BloodshotEvents::onUseFinish);
        NeoForge.EVENT_BUS.addListener(BloodshotEvents::onEntityJoin);
        NeoForge.EVENT_BUS.addListener(EarthquakeEvents::onLivingIncomingDamage);
        NeoForge.EVENT_BUS.addListener(
                org.conjuration.chant_to_enchant.enchantments.EngagedEvents::onBreakSpeed
        );
        NeoForge.EVENT_BUS.addListener(
                org.conjuration.chant_to_enchant.enchantments.EngagedEvents::onBlockBreak
        );
        NeoForge.EVENT_BUS.addListener(
                org.conjuration.chant_to_enchant.enchantments.HexlashEvents::onDamagePre
        );
        NeoForge.EVENT_BUS.addListener(ChainsEvents::onIncomingDamage);
        NeoForge.EVENT_BUS.addListener(ChainsEvents::onLevelTick);
        NeoForge.EVENT_BUS.addListener(
                org.conjuration.chant_to_enchant.enchantments.VortexEvents::onProjectileHit
        );
        NeoForge.EVENT_BUS.addListener(
                org.conjuration.chant_to_enchant.enchantments.VortexEvents::onLevelTick
        );
        NeoForge.EVENT_BUS.addListener(StealthEvents::onLevelTick);
        NeoForge.EVENT_BUS.addListener(StealthEvents::onDamage);
        NeoForge.EVENT_BUS.addListener(DodgeEvents::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(DodgeEvents::onIncomingDamage);
        NeoForge.EVENT_BUS.addListener(
                org.conjuration.chant_to_enchant.enchantments.GustEvents::onDamage
        );
        NeoForge.EVENT_BUS.addListener(
                org.conjuration.chant_to_enchant.enchantments.GustEvents::onEntityTick
        );
        NeoForge.EVENT_BUS.addListener(
                org.conjuration.chant_to_enchant.enchantments.IntuitionEvents::onEntityTick
        );



        LOGGER.info("[{}] Common event listeners registered.", MODID);
    }
}
