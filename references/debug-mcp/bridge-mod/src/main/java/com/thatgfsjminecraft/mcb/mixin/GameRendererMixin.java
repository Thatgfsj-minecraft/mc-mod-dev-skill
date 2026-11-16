package com.thatgfsjminecraft.mcb.mixin;

import com.thatgfsjminecraft.mcb.McbCamera;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
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

    // cancellable is REQUIRED: the TAIL injection calls cir.setReturnValue(...) when the
    // fov override is active, and without this flag Mixin throws CancellationException on
    // the render thread ("The call method_3196 is not cancellable") - crashed the client
    // the moment persistent camera mode pinned the fov (crash-2026-11-15_16.35.20).
    @Inject(method = "getFov(Lnet/minecraft/client/Camera;FZ)F", at = @At("TAIL"), cancellable = true)
    private void mcb$fovOverride(Camera camera, float partialTick, boolean useFovSetting,
                                 CallbackInfoReturnable<Float> cir) {
        if (McbCamera.hasFovOverride()) {
            cir.setReturnValue(McbCamera.fovOverride());
        }
    }

    /**
     * Hide the player's own first-person hand while the sky-eye owns the view.
     *
     * The hand is NOT part of the Gui class - it is drawn by
     * GameRenderer.renderItemInHand(float, boolean, Matrix4f) during the world
     * render pass, so GuiMixin cannot suppress it. Leaving it in produced
     * screenshots with a giant arm in the corner of an otherwise clean view.
     */
    @Inject(method = "renderItemInHand(FZLorg/joml/Matrix4f;)V", at = @At("HEAD"), cancellable = true)
    private void mcb$suppressHand(float partialTick, boolean swingArm, Matrix4f poseStack,
                                  CallbackInfo ci) {
        if (McbCamera.uiSuppressed()) {
            ci.cancel();
        }
    }
}
