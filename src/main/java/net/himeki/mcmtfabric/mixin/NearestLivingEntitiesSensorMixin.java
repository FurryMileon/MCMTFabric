package net.himeki.mcmtfabric.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.brain.sensor.NearestLivingEntitiesSensor;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(NearestLivingEntitiesSensor.class)
public class NearestLivingEntitiesSensorMixin<T extends LivingEntity> {

    @WrapMethod(method = "sense")
    private synchronized void syncSense(ServerWorld world, T entity, Operation<Void> original) {
        original.call(world, entity);
    }
}
