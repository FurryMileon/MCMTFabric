package net.himeki.mcmtfabric.debug;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.Arm;
import net.minecraft.world.World;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class MSPT10DebugEntity extends MobEntity {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final long TARGET_TIME_MS = 10;
    private static volatile double result; // Prevent JVM optimization
    private static final TrackedData<Boolean> ACTIVE = DataTracker.registerData(MSPT10DebugEntity.class, TrackedDataHandlerRegistry.BOOLEAN);

    public MSPT10DebugEntity(EntityType<? extends MSPT10DebugEntity> entityType, World world) {
        super(entityType, world);
        LOGGER.info("Creating new MSPT10DebugEntity");
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return LivingEntity.createLivingAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 1.0) // Only 1 health point
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.0) // Doesn't move
                .add(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 1.0) // Doesn't get knocked back
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 0.0);
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        super.initDataTracker(builder);
        builder.add(ACTIVE, true);
    }

    @Override
    public void tick() {
        super.tick();
        if (!getWorld().isClient && getDataTracker().get(ACTIVE)) {
            spinCPU(TARGET_TIME_MS);
        }
    }

    @Override
    public Arm getMainArm() {
        return null;
    }

    private static void spinCPU(long targetMs) {
        long startTime = System.nanoTime();
        long targetNanos = targetMs * 1_000_000;
        double x = Math.PI;

        while (System.nanoTime() - startTime < targetNanos) {
            x = Math.sin(x) * Math.cos(x);
            x = Math.pow(x, 1.1);
            x = Math.sqrt(Math.abs(x));
            x += Math.PI;
            result = x;
        }
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        if (nbt.contains("Active")) {
            getDataTracker().set(ACTIVE, nbt.getBoolean("Active"));
        }
    }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putBoolean("Active", getDataTracker().get(ACTIVE));
    }

    // Required methods for LivingEntity
    @Override
    public Iterable<net.minecraft.item.ItemStack> getArmorItems() {
        return java.util.Collections.emptyList();
    }

    @Override
    public net.minecraft.item.ItemStack getEquippedStack(net.minecraft.entity.EquipmentSlot slot) {
        return net.minecraft.item.ItemStack.EMPTY;
    }

    @Override
    public void equipStack(net.minecraft.entity.EquipmentSlot slot, net.minecraft.item.ItemStack stack) {}
}