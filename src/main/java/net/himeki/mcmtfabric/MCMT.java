package net.himeki.mcmtfabric;

import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.ConfigHolder;
import me.shedaniel.autoconfig.serializer.Toml4jConfigSerializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.himeki.mcmtfabric.commands.ConfigCommand;
import net.himeki.mcmtfabric.commands.StatsCommand;
import net.himeki.mcmtfabric.config.GeneralConfig;
import net.himeki.mcmtfabric.config.ThreadedRangesConfig;
import net.himeki.mcmtfabric.debug.MSPT10DebugBlock;
import net.himeki.mcmtfabric.debug.MSPT10DebugBlockEntity;
import net.himeki.mcmtfabric.jmx.JMXRegistration;
import net.himeki.mcmtfabric.serdes.SerDesRegistry;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class MCMT implements ModInitializer {
    public static final Logger LOGGER = LogManager.getLogger();
    public static GeneralConfig config;
    public static ThreadedRangesConfig threadedRangesConfig;

    public static final MSPT10DebugBlock MSPT10_DEBUG_BLOCK = Registry.register(
            Registries.BLOCK,
            Identifier.of("mcmtfabric", "mspt10_debug_block"),
            new MSPT10DebugBlock(AbstractBlock.Settings.create())
    );

    // Register the block entity type
    public static final BlockEntityType<MSPT10DebugBlockEntity> MSPT10_DEBUG_BLOCK_ENTITY = Registry.register(
            Registries.BLOCK_ENTITY_TYPE,
            Identifier.of("mcmtfabric", "mspt10_debug_block"),
            BlockEntityType.Builder.create(MSPT10DebugBlockEntity::new, MSPT10_DEBUG_BLOCK).build()
    );

    public static final BlockItem MSPT10DebugITEM = Registry.register(
            Registries.ITEM,
            Identifier.of("mcmtfabric", "mspt10_debug_block"),
            new BlockItem(MSPT10_DEBUG_BLOCK, new Item.Settings())
    );
    
    @Override
    public void onInitialize() {
        // This code runs as soon as Minecraft is in a mod-load-ready state.
        // However, some things (like resources) may still be uninitialized.
        // Proceed with mild caution.
        LOGGER.info("Initializing MCMTFabric...");
        ConfigHolder<GeneralConfig> holder = AutoConfig.register(GeneralConfig.class, Toml4jConfigSerializer::new);
        holder.registerLoadListener((manager, data) -> {
            holder.getConfig().loadTELists();
            return ActionResult.SUCCESS;
        });
        holder.load();  // Load again to run loadTELists() handler
        config = holder.getConfig();

        ConfigHolder<ThreadedRangesConfig> trHolder = AutoConfig.register(ThreadedRangesConfig.class, Toml4jConfigSerializer::new);
        trHolder.load();

        trHolder.getConfig().threadedRanges.forEach(ParallelProcessor::addThreadedChunksRange);

        if (System.getProperty("jmt.mcmt.jmx") != null) {
            JMXRegistration.register();
        }

        StatsCommand.runDataThread();
        SerDesRegistry.init();


        LOGGER.info("MCMT Setting up threadpool...");
        ParallelProcessor.setupThreadPool(GeneralConfig.getParallelism());


        // Listener reg begin
        ServerLifecycleEvents.SERVER_STARTED.register(server -> StatsCommand.resetAll());
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> ConfigCommand.register(dispatcher));

    }
}
