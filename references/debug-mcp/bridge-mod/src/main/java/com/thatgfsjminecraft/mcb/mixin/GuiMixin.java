package com.thatgfsjminecraft.mcb.mixin;

import com.thatgfsjminecraft.mcb.McbCamera;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sky-eye UI suppression (protocol §3.12, user requirement).
 *
 * While a camera op owns the view, hide the VANILLA HUD clutter so the
 * screenshot shows the world, not the player's interface. Mod information
 * overlays (Jade tooltip, minihud, ...) are NOT touched: they render through
 * their own hooks (fabric HudRenderCallback / their own mixins), which stay
 * enabled — that is the whole point: the AI gets a clean shot that still
 * carries the Jade block info at the virtual crosshair.
 *
 * Suppressed elements (all private per-element renderers of Gui, verified on
 * 1.21.11): hotbar + health/food/xp decorations, status effects, chat +
 * actionbar, tab list, scoreboard sidebar, boss bar, subtitles, crosshair,
 * overlay (actionbar) message, demo + sleep overlays. Screen effects like
 * vignette/portal (renderCameraOverlays) are deliberately KEPT — they belong
 * to the world's look, not the UI.
 */
@Mixin(Gui.class)
public abstract class GuiMixin {

    @Inject(method = {
            "renderHotbarAndDecorations",
            "renderEffects",
            "renderChat",
            "renderTabList",
            "renderScoreboardSidebar",
            "renderBossOverlay",
            "renderSubtitleOverlay",
            "renderCrosshair",
            "renderOverlayMessage",
            "renderDemoOverlay",
            "renderSleepOverlay",
    }, at = @At("HEAD"), cancellable = true)
    private void mcb$suppressVanillaHud(CallbackInfo ci) {
        if (McbCamera.uiSuppressed()) {
            ci.cancel();
        }
    }
}
