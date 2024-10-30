package net.himeki.mcmtfabric.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.brain.sensor.NearestLivingEntitiesSensor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Mixin(NearestLivingEntitiesSensor.class)
public class NearestLivingEntitiesSensorMixin<T extends LivingEntity> {

//    @WrapMethod(method = "sense")
//    private synchronized void syncSense(ServerWorld world, T entity, Operation<Void> original) {
//        original.call(world, entity);
//    }

    @ModifyExpressionValue(method = "sense", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/world/ServerWorld;getEntitiesByClass(Ljava/lang/Class;Lnet/minecraft/util/math/Box;Ljava/util/function/Predicate;)Ljava/util/List;"))
    private synchronized List<LivingEntity> syncSense(List<LivingEntity> original) {
        return new ArrayList<>(original.stream()
                .filter(Objects::nonNull)
                .toList());
    }
}
