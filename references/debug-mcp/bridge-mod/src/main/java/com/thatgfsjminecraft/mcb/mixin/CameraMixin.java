package com.thatgfsjminecraft.mcb.mixin;

import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import com.thatgfsjminecraft.mcb.McbCamera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * "Sky-eye" camera hook (protocol §3.12).
 *
 * Camera.setup is called once per frame from GameRenderer.updateCamera —
 * BEFORE renderLevel AND before the crosshair pick inside it. Overwriting the
 * pose at TAIL therefore affects:
 *   - what the frame renders (the screenshot), and
 *   - GameRenderer.pick's raycast => Jade/WTHIT crosshair tooltips follow the
 *     virtual camera, which gives us "stand anywhere and look at any block"
 *     for free.
 *
 * setRotation(yRot, xRot) argument order verified on 1.21.11 via bytecode:
 * arg0 -> yRot (yaw), arg1 -> xRot (pitch).
 */
@Mixin(Camera.class)
public abstract class CameraMixin {

    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    @Shadow
    protected abstract void setPosition(Vec3 pos);

    @Inject(method = "setup(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;ZZF)V",
            at = @At("TAIL"))
    private void mcb$applyFreeCamera(Level level, Entity entity, boolean detached,
                                     boolean mirrored, float partialTick, CallbackInfo ci) {
        if (!McbCamera.overrideActive()) return;
        this.setPosition(new Vec3(McbCamera.camX(), McbCamera.camY(), McbCamera.camZ()));
        this.setRotation(McbCamera.camYaw(), McbCamera.camPitch());
        McbCamera.onSetupApplied();
    }
}
