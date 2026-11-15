package com.thatgfsjminecraft.mcb.mixin;

import com.thatgfsjminecraft.mcb.McbCamera;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Optional fov override for the sky-eye camera (protocol §3.12 camera.* "fov").
 *
 * getFov(Camera, float, boolean) is still the single source of the world
 * projection fov on 1.21.11 (verified via javap; the 1.21.2+ projection-matrix
 * rework kept this method). Overriding the RETURN value at TAIL keeps all the
 * vanilla fov modifiers (sprinting, effect nausea, spyglass) out of the way
 * and simply pins the projection to the requested value.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(method = "getFov(Lnet/minecraft/client/Camera;FZ)F", at = @At("TAIL"))
    private void mcb$fovOverride(Camera camera, float partialTick, boolean useFovSetting,
                                 CallbackInfoReturnable<Float> cir) {
        if (McbCamera.hasFovOverride()) {
            cir.setReturnValue(McbCamera.fovOverride());
        }
    }
}
