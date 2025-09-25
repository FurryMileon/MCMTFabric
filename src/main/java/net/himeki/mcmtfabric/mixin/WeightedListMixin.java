package net.himeki.mcmtfabric.mixin;

import net.minecraft.util.collection.WeightedList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Mixin(WeightedList.class)
public abstract class WeightedListMixin<T> {

    @Shadow
    @Final
    @Mutable
    private List<WeightedList.Entry<T>> entries;

    @Inject(method = "<init>()V", at = @At("TAIL"))
    private void mcmt$wrapEntries(CallbackInfo ci) {
        this.entries = new CopyOnWriteArrayList<>(this.entries);
    }

    @Inject(method = "<init>(Ljava/util/List;)V", at = @At("TAIL"))
    private void mcmt$wrapEntriesWithList(List<WeightedList.Entry<T>> entries, CallbackInfo ci) {
        this.entries = new CopyOnWriteArrayList<>(this.entries);
    }
}
