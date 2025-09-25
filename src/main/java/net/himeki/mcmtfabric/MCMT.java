package net.himeki.mcmtfabric;

import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.ConfigHolder;
import me.shedaniel.autoconfig.serializer.Toml4jConfigSerializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.himeki.mcmtfabric.commands.ConfigCommand;
import net.himeki.mcmtfabric.commands.StatsCommand;
import net.himeki.mcmtfabric.config.GeneralConfig;
import net.himeki.mcmtfabric.debug.MSPT10DebugBlock;
import net.himeki.mcmtfabric.debug.MSPT10DebugBlockEntity;
import net.himeki.mcmtfabric.debug.MSPT10DebugEntity;
import net.himeki.mcmtfabric.debug.MSPT10DebugEntityRenderer;
import net.himeki.mcmtfabric.jmx.JMXRegistration;
import net.himeki.mcmtfabric.parallelised.BotRegionManager;
import net.himeki.mcmtfabric.serdes.SerDesRegistry;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Objects;

public class MCMT implements ModInitializer {
    public static final Logger LOGGER = LogManager.getLogger();
    public static GeneralConfig config;

    // Declare these as fields but don't initialize them immediately
    public static MSPT10DebugBlock MSPT10_DEBUG_BLOCK;
    public static BlockEntityType<MSPT10DebugBlockEntity> MSPT10_DEBUG_BLOCK_ENTITY;
    public static BlockItem MSPT10DebugITEM;

    public static EntityType<MSPT10DebugEntity> MSPT10_DEBUG_ENTITY;

    private void registerDebugBlocks() {

        LOGGER.info("Debug mode enabled, registering debug blocks...");

        MSPT10_DEBUG_BLOCK = Registry.register(
                Registries.BLOCK,
                Identifier.of("mcmtfabric", "mspt10_debug_block"),
                new MSPT10DebugBlock(AbstractBlock.Settings.create())
        );

        MSPT10_DEBUG_BLOCK_ENTITY = Registry.register(
                Registries.BLOCK_ENTITY_TYPE,
                Identifier.of("mcmtfabric", "mspt10_debug_block"),
                BlockEntityType.Builder.create(MSPT10DebugBlockEntity::new, MSPT10_DEBUG_BLOCK).build()
        );

        MSPT10DebugITEM = Registry.register(
                Registries.ITEM,
                Identifier.of("mcmtfabric", "mspt10_debug_block"),
                new BlockItem(MSPT10_DEBUG_BLOCK, new Item.Settings())
        );

        LOGGER.info("Debug blocks registered successfully");

    }

    private void registerDebugEntities() {
        LOGGER.info("Registering debug entities...");

        MSPT10_DEBUG_ENTITY = Registry.register(
                Registries.ENTITY_TYPE,
                Identifier.of("mcmtfabric", "mspt10_debug_entity"),
                EntityType.Builder.create(MSPT10DebugEntity::new, SpawnGroup.MISC).dimensions(0.6F, 2.0F).build()
        );

        EntityRendererRegistry.register(MCMT.MSPT10_DEBUG_ENTITY, MSPT10DebugEntityRenderer::new);
        FabricDefaultAttributeRegistry.register(MSPT10_DEBUG_ENTITY, MSPT10DebugEntity.createAttributes());

        LOGGER.info("Debug entities registered successfully");
    }

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing MCMTFabric...");

        // Register debug blocks and entities based on environment variable
        if (Objects.requireNonNullElse(System.getProperty("MCMT_ENABLE_DEBUG"), "").equals("true")) {
            registerDebugBlocks();
            registerDebugEntities();
        } else {
            LOGGER.info("Debug mode disabled, skipping debug block and entity registration");
        }

        ConfigHolder<GeneralConfig> holder = AutoConfig.register(GeneralConfig.class, Toml4jConfigSerializer::new);
        holder.registerLoadListener((manager, data) -> {
            holder.getConfig().loadTELists();
            return ActionResult.SUCCESS;
        });
        holder.load();  // Load again to run loadTELists() handler
        config = holder.getConfig();

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

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                BotRegionManager.cleanup(handler.player.getName().toString()));

        LOGGER.info("MCMT Initialized");
    }
}
