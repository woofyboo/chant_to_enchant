package org.conjuration.chant_to_enchant;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

@Mod(Chant_to_enchant.MODID)
public class Chant_to_enchant {
    public static final String MODID = "chant_to_enchant";

    public Chant_to_enchant(IEventBus modEventBus, ModContainer modContainer) {
        // Если конфиги не нужны — можешь удалить эту строку и сам класс Config.
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        // Больше ничего не регистрируем: ни блоков, ни айтемов, ни вкладок, ни эвентов.
    }
}
