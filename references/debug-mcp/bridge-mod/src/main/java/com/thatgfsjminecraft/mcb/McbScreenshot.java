package com.thatgfsjminecraft.mcb;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.util.Util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Consumer;

/**
 * The two GL-read paths of the bridge.
 *
 * Verified 1.21.11 (javap + decompiled source, see version card bridge.md):
 * - net.minecraft.client.Screenshot.takeScreenshot(RenderTarget, Consumer<NativeImage>)
 *   — callback style; the pixels are copied on the render thread's command
 *   stream, the consumer receives the NativeImage;
 * - NativeImage has NO asByteArray; PNG encoding goes through writeToFile, so we
 *   always use vanilla's own encoder (no third-party dependency);
 * - Minecraft.getMainRenderTarget() is the framebuffer that holds the frame
 *   (world + HUD + screens) until the next frame starts.
 *
 * GL pixels can only be read on the render thread, so both entries below are
 * called from the client main/render thread (McbServer hops first).
 */
final class McbScreenshot {
    private McbScreenshot() {}

    /**
     * §3.11 screenshot.take — base64 inline (unchanged since 0.1.0).
     *
     * Writes a temp file and reads the bytes back because NativeImage can only
     * encode PNG through writeToFile.
     */
    static void take(Consumer<JsonObject> onOk, Consumer<String> onError) {
        Minecraft.getInstance().execute(() -> {
            try {
                var fb = Minecraft.getInstance().getMainRenderTarget();
                Screenshot.takeScreenshot(fb, image -> {
                    try (var img = image) {
                        Path tmp = Files.createTempFile("mcb-shot-", ".png");
                        byte[] png;
                        try {
                            img.writeToFile(tmp);
                            png = Files.readAllBytes(tmp);
                        } finally {
                            Files.deleteIfExists(tmp);
                        }
                        JsonObject data = new JsonObject();
                        data.addProperty("format", "png");
                        data.addProperty("width", fb.width);
                        data.addProperty("height", fb.height);
                        data.addProperty("dataBase64",
                                java.util.Base64.getEncoder().encodeToString(png));
                        onOk.accept(data);
                    } catch (Exception e) {
                        onError.accept(e.getClass().getName() + ": " + e.getMessage());
                    }
                });
            } catch (Exception e) {
                onError.accept(e.getClass().getName() + ": " + e.getMessage());
            }
        });
    }

    /**
     * §3.16 client.screenshot — capture the CURRENT frame and write it to
     * {@code <gameDirectory>/screenshots/<filename>}.
     *
     * WHY NOT Screenshot.grab(...) (spec §3.1.3): grab() takes the screenshot and
     * then asynchronously writes the file itself, so we could neither restore
     * hideGui exactly when the pixels are read, nor know the absolute target path
     * before the file exists. takeScreenshot + our own NativeImage.writeToFile
     * gives both, and keeps the response semantics ("文件写完后才响应") trivial.
     *
     * TIMING: called from GameRendererMixin at RETURN of render(...) — the frame
     * (world + HUD + open Screen) is fully drawn at that point, see the mixin
     * comment. Pixel read happens immediately; encoding + file IO are handed to
     * Util.ioPool() so the render thread never blocks on disk (protocol §6).
     *
     * @param pixelsReady runs on the render thread right after the pixels are in
     *                    the NativeImage (hide_hud restore point, spec §3.1.2);
     *                    may be null.
     * @param meta        extra response fields merged before the file facts
     *                    (hide_hud / screen_class), may be null.
     */
    static void captureToFile(String filename, JsonObject meta, Runnable pixelsReady,
                              Consumer<JsonObject> onOk, Consumer<String> onError) {
        Minecraft mc = Minecraft.getInstance();
        try {
            RenderTarget fb = mc.getMainRenderTarget();
            Screenshot.takeScreenshot(fb, image -> {
                // The pixels are now in our own NativeImage, so the one-frame
                // state takeover (options.hideGui) can be released without
                // affecting what was just captured.
                if (pixelsReady != null) {
                    try {
                        pixelsReady.run();
                    } catch (Throwable ignored) {
                        // restore must never break the screenshot itself
                    }
                }
                Util.ioPool().execute(() -> writeImage(mc, filename, meta, image, onOk, onError));
            });
        } catch (Throwable t) {
            // takeScreenshot throws (e.g. incomplete framebuffer) instead of
            // calling back — release the takeover and report honestly.
            if (pixelsReady != null) {
                try {
                    pixelsReady.run();
                } catch (Throwable ignored) {
                }
            }
            onError.accept("screenshot capture failed: " + t);
        }
    }

    /** IO-pool side of captureToFile: PNG encode, write, then answer. */
    private static void writeImage(Minecraft mc, String filename, JsonObject meta,
                                   com.mojang.blaze3d.platform.NativeImage image,
                                   Consumer<JsonObject> onOk, Consumer<String> onError) {
        try (var img = image) {
            // Same directory vanilla uses (Screenshot.SCREENSHOT_DIR = "screenshots").
            Path dir = Paths.get(mc.gameDirectory.getAbsolutePath(), Screenshot.SCREENSHOT_DIR);
            Files.createDirectories(dir);
            Path target = dir.resolve(filename);
            // NativeImage.writeToFile(Path) is the only PNG encoder available to us.
            img.writeToFile(target);
            JsonObject data = new JsonObject();
            if (meta != null) {
                for (var entry : meta.entrySet()) {
                    data.add(entry.getKey(), entry.getValue());
                }
            }
            data.addProperty("path", target.toAbsolutePath().toString());
            data.addProperty("width", img.getWidth());
            data.addProperty("height", img.getHeight());
            data.addProperty("bytes", Files.size(target));
            onOk.accept(data);
        } catch (Throwable t) {
            onError.accept(t.getClass().getName() + ": " + t.getMessage());
        }
    }
}
