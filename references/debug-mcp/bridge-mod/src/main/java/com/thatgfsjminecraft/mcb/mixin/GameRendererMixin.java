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
import org.spongepowered.asm.mixin.injection.Redirect;
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
    // §1.2 focus-pause guard (client.screenshot / screen.current depend on it)
    //
    // Target signature verified against the decompiled 1.21.11 sources
    // (/tmp/mc1211all): GameRenderer.render(DeltaTracker, boolean) contains
    // exactly ONE pauseGame call, in the focus-loss branch:
    //
    //   if (!this.minecraft.isWindowActive() && this.minecraft.options.pauseOnLostFocus
    //       && (!touchscreen || !mouseHandler.isRightPressed())) {
    //       if (Util.getMillis() - this.lastActiveTime > 500L) {
    //           this.minecraft.pauseGame(false);      // <- @Redirect target
    //       }
    //   }
    //
    // WHY ONLY THIS CALL SITE (hard constraint, spec §1.2 + pitfalls E3):
    // Minecraft.pauseGame(boolean) is NOT focus-loss specific — the ESC key path
    // also calls pauseGame(false) from KeyboardHandler. Cancelling/mixin-ing
    // pauseGame(boolean) itself would silently break the ESC pause menu.
    // Redirecting only the GameRenderer invocation leaves ESC untouched.
    //
    // WHY A PLAIN @Redirect and not MixinExtras @WrapOperation (pitfalls E2):
    // this bridge mod is a zero-dependency reference implementation (no
    // fabric-api, no MixinExtras on the classpath); real mods should prefer
    // @WrapOperation for cross-mod compatibility.
    // =====================================================================
    @Redirect(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/client/Minecraft;pauseGame(Z)V"))
    private void mcb$guardFocusPause(Minecraft mc, boolean bl) {
        if (McbClientOps.autoPauseGuard()) {
            return; // automated run: losing OS focus must not freeze the game
        }
        mc.pauseGame(bl); // guard off (client.pause_on_lost_focus enabled=true) -> vanilla
    }

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
