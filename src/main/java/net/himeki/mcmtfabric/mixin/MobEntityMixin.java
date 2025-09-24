package net.himeki.mcmtfabric.mixin;

import net.himeki.mcmtfabric.mixin.access.GoalSelectorAccess;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.goal.GoalSelector;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MobEntity.class)
public abstract class MobEntityMixin {

    @Shadow
    protected GoalSelector goalSelector;

    @Shadow
    protected GoalSelector targetSelector;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void mcmt$assignSelectorOwners(EntityType<? extends MobEntity> type, World world, CallbackInfo ci) {
        GoalSelectorAccess goalAccess = (GoalSelectorAccess) this.goalSelector;
        goalAccess.mcmt$setOwner((MobEntity) (Object) this);
        GoalSelectorAccess targetAccess = (GoalSelectorAccess) this.targetSelector;
        targetAccess.mcmt$setOwner((MobEntity) (Object) this);
    }
}
