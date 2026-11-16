package com.thatgfsjminecraft.mcb.mixin;

import com.thatgfsjminecraft.mcb.McbClientOps;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Client-tick hook for the "politely queued" developer-mode prompt.
 *
 * <p>WHY A MIXIN: the prompt must be evaluated on the client tick (after the
 * loading overlay disappears and only while a TitleScreen / custom title screen
 * is showing). The reference bridge is intentionally zero-dependency (no
 * fabric-api / ClientTickEvents), so a single TAIL {@code @Inject} is the
 * narrowest hook.
 *
 * <p>Only {@code @Inject} is used — never {@code @Redirect} — because the
 * {@code @Redirect.at()} annotation encoding differs between sponge-mixin
 * 0.17.0 (single At) and 0.17.4 (At[]) and crashed the client at startup in
 * 0.2.0 on the older runtime. {@code @Inject.at()} is stable on both.
 */
@Mixin(Minecraft.class)
public class MinecraftMixin {
    @Inject(method = "tick", at = @At("RETURN"))
    private void mcb$onClientTick(CallbackInfo ci) {
        McbClientOps.onClientTick();
    }
}
