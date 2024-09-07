package net.himeki.mcmtfabric.mixin;

import com.mojang.datafixers.DataFixer;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.himeki.mcmtfabric.parallelised.fastutil.Int2ObjectConcurrentHashMap;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ServerChunkLoadingManager;
import net.minecraft.world.ChunkLoadingManager;
import net.minecraft.world.storage.StorageKey;
import net.minecraft.world.storage.VersionedChunkStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

import java.nio.file.Path;

@Mixin(ServerChunkLoadingManager.class)
public abstract class ServerChunkLoadingManagerMixin extends VersionedChunkStorage implements ChunkHolder.PlayersWatchingChunkProvider, ChunkLoadingManager {

    public ServerChunkLoadingManagerMixin(StorageKey storageKey, Path directory, DataFixer dataFixer, boolean dsync) {
        super(storageKey, directory, dataFixer, dsync);
    }

    @Shadow
    @Final
    @Mutable
    private Int2ObjectMap<ServerChunkLoadingManager.EntityTracker> entityTrackers = new Int2ObjectConcurrentHashMap<>();
}