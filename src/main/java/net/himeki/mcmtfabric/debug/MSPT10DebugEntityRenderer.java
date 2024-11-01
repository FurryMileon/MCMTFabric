package net.himeki.mcmtfabric.debug;

import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import net.minecraft.client.render.debug.DebugRenderer;

public class MSPT10DebugEntityRenderer extends EntityRenderer<MSPT10DebugEntity> {
    private static final Identifier TEXTURE = Identifier.of("mcmtfabric", "textures/entity/mspt10_debug_entity.png");

    public MSPT10DebugEntityRenderer(EntityRendererFactory.Context context) {
        super(context);
    }

    @Override
    public void render(MSPT10DebugEntity entity, float yaw, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vertexConsumers, int light) {
        super.render(entity, yaw, tickDelta, matrices, vertexConsumers, light);

        // Draw a debug hitbox-style outline
        Box box = entity.getBoundingBox().offset(
                -entity.getX(),
                -entity.getY(),
                -entity.getZ()
        );

        // Get the vertex consumer for debug lines
        Vec3d cameraPos = this.dispatcher.camera.getPos();
        matrices.push();
        matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        // Draw debug outline using the built-in debug renderer
        DebugRenderer.drawBox(
                matrices,
                vertexConsumers,
                box.offset(entity.getX(), entity.getY(), entity.getZ()),
                1.0f, 0.0f, 1.0f, 1.0f  // Purple color (RGBA)
        );

        matrices.pop();
    }

    @Override
    public Identifier getTexture(MSPT10DebugEntity entity) {
        return TEXTURE;
    }
}