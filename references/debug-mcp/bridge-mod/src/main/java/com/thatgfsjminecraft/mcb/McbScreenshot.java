package com.thatgfsjminecraft.mcb;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * §3.11 screenshot.take.
 *
 * Verified 1.21.11 (javap, see version card bridge.md):
 * - net.minecraft.client.Screenshot.takeScreenshot(RenderTarget, Consumer<NativeImage>)
 *   — callback style, consumer fires synchronously on the render thread;
 * - NativeImage has NO asByteArray; PNG encoding goes through writeToFile,
 *   so we write a temp file and read the bytes back (vanilla's own encoder,
 *   no third-party dependency).
 *
 * GL pixels can only be read on the render thread, so take() is called from
 * the TCP thread and hops via Minecraft.execute().
 */
final class McbScreenshot {
    private McbScreenshot() {}

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
}
