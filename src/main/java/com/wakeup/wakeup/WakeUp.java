package com.wakeup.wakeup;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * Entry point of the Wake Up mod.
 *
 * <p>The mod implements a server-wide "dream" mechanic: when players sleep through
 * the night, there is a configurable chance that the whole server is snapshotted and
 * enters a timed dream. When the dream ends, the server rolls back to that snapshot.
 * Nested dreams (dream within a dream) are supported via a snapshot stack.</p>
 */
@Mod(WakeUp.MODID)
public final class WakeUp {

    public static final String MODID = "wakeup";

    public WakeUp(IEventBus modEventBus, ModContainer container) {
        // COMMON-type config: editable from the main menu and in-game (config/wakeup-common.toml).
        container.registerConfig(ModConfig.Type.COMMON, WakeUpConfig.SPEC);
        // In-game config screen, opened from the Mods list.
        container.registerExtensionPoint(IConfigScreenFactory.class,
                (IConfigScreenFactory) (mc, parent) -> new ConfigurationScreen(mc, parent));
    }
}
