package com.thatgfsjminecraft.mcb.mixin;

import com.thatgfsjminecraft.mcb.McbCamera;
import com.thatgfsjminecraft.mcb.McbClientOps;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
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

    // =====================================================================
    // §1.2 focus-pause guard — deliberately NOT an @Redirect (see below)
    //
    // The guard used to be an `@Redirect` on the single focus-loss
    // `Minecraft.pauseGame(false)` call inside GameRenderer.render. That was
    // REMOVED after a real startup crash:
    //
    //   MixinExtras.preApply -> ClassCastException: java.util.ArrayList cannot
    //   be cast to org.objectweb.asm.tree.AnnotationNode
    //   -> "Mixin transformation of net.minecraft.class_757 (GameRenderer) failed"
    //
    // ROOT CAUSE: @Redirect.at() is version-dependent in the Mixin annotation
    // class itself. The build compiles against sponge-mixin 0.17.4+mixin.0.8.7
    // (fabric-loader 0.19.5), whose `@Redirect.at()` returns `At[]`; the runtime
    // client here uses sponge-mixin 0.17.0+mixin.0.8.7 (fabric-loader 0.18.4),
    // whose `@Redirect.at()` returns a single `At`. javac therefore encoded
    // `at = {@At(...)}` and the runtime Mixin/Fabric MixinExtras reader cast the
    // ArrayList to AnnotationNode and crashed. `@Inject.at()` is `At[]` in both
    // versions - that is why the @Inject hooks below are safe, and why the
    // bridge must not use @Redirect at all. (A template that has to run across
    // loader/Mixin versions cannot rely on @Redirect's annotation wire format.)
    //
    // Replacement guard: McbClientOps.re-asserts options.pauseOnLostFocus=false
    // once per rendered frame while autoPauseGuard is on, from the @Inject
    // RETURN hook below. That is annotation-version-stable, and it also keeps
    // ESC working: ESC calls Minecraft.pauseGame directly and does not read
    // this option - the option only gates the focus-loss branch.
    // =====================================================================

    // =====================================================================
    // §3.16 client.screenshot frame-end hook.
    //
    // At RETURN of render(...) the frame is fully drawn: on 1.21.11 the vanilla
    // HUD (gui.render) AND any open Screen are rendered INSIDE
    // GameRenderer.render (verified: lines ~514-583 of the decompiled source),
    // and the blit to the window happens later in Minecraft.runTick. Reading the
    // render target here therefore captures exactly the frame the user sees,
    // screenshot semantics included.
    //
    // Runs on the render thread = client main thread on 1.21.11; the actual
    // capture/write is McbScreenshot.captureToFile (pixels here, IO on
    // Util.ioPool()).
    // =====================================================================
    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("RETURN"))
    private void mcb$onFrameEnd(DeltaTracker deltaTracker, boolean bl, CallbackInfo ci) {
        McbClientOps.onFrameEnd();
    }
}
