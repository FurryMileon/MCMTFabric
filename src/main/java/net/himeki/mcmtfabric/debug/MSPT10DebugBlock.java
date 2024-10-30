package net.himeki.mcmtfabric.debug;

import com.mojang.serialization.MapCodec;
import net.himeki.mcmtfabric.MCMT;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class MSPT10DebugBlock extends BlockWithEntity {
    private static final Logger LOGGER = LogManager.getLogger();

    public static final MapCodec<MSPT10DebugBlock> CODEC = createCodec(MSPT10DebugBlock::new);

    public MSPT10DebugBlock(AbstractBlock.Settings settings) {
        super(settings);
    }

    @Override
    protected MapCodec<? extends MSPT10DebugBlock> getCodec() {
        return CODEC;
    }

    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        LOGGER.info("Creating block entity at {}", pos);
        return new MSPT10DebugBlockEntity(pos, state);
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.MODEL;
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        return !world.isClient ? validateTicker(type, MCMT.MSPT10_DEBUG_BLOCK_ENTITY, MSPT10DebugBlockEntity::tick) : null;
    }
}
