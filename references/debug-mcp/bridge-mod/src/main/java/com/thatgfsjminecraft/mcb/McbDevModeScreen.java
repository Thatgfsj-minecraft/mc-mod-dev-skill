package com.thatgfsjminecraft.mcb;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The one-time "enable developer mode?" confirm screen.
 *
 * <p>Design constraints (why this is a standalone ConfirmScreen instead of a
 * button injected into {@code TitleScreen}):
 * <ul>
 *   <li>The title screen layout is never modified, so mod-menu / FancyMenu /
 *       ReplayMod style main-menu mods cannot collide with an injected button;</li>
 *   <li>the current screen is captured as the {@code parent} and restored after
 *       the answer — even when another mod replaced the main menu wholesale;</li>
 *   <li>the screen is only opened from the client tick after the loading overlay
 *       is gone and another mod's prompt has closed (see
 *       {@link McbClientOps#onClientTick()}).</li>
 * </ul>
 *
 * <p>The optional "remember this choice" checkbox persists the answer through
 * {@link McbDevModeConfig#writeChoice(boolean)}; answering does not repeat in
 * this JVM because McbClientOps sets its session flag before opening us.
 */
public final class McbDevModeScreen extends ConfirmScreen {
    private static final Component TITLE = Component.literal("是否开启开发者模式？");
    private static final Component YES = Component.literal("是");
    private static final Component NO = Component.literal("否");
    private static final Component REMEMBER = Component.literal("记住本次选择");

    private final boolean[] remember;
    private Checkbox rememberBox;

    private McbDevModeScreen(Screen parent, boolean[] remember, String sourceName, String sourceId) {
        super(choice -> handleDecision(parent, remember, choice),
                TITLE,
                message(sourceName, sourceId),
                YES,
                NO);
        this.remember = remember;
    }

    /** Build the prompt, capturing the current screen as parent. */
    static McbDevModeScreen create(Screen parent) {
        return new McbDevModeScreen(parent, new boolean[] { false },
                McbDevModeConfig.sourceName(), McbDevModeConfig.sourceId());
    }

    private static Component message(String sourceName, String sourceId) {
        return Component.literal("来源：" + sourceName + "（" + sourceId + "）" + "\n" + "\n"
                + "开启后将启动本地调试服务（127.0.0.1:" + McbMod.resolvePort()
                + "）并关闭窗口失焦暂停。" + "\n"
                + "调试服务仅监听本机回环地址，供 AI / 自动化调试使用。");
    }

    private static void handleDecision(Screen parent, boolean[] remember, boolean choice) {
        McbMod.LOGGER.info("[MCB] dev-mode prompt answered: dev_mode={} remember={}", choice, remember[0]);
        if (remember[0]) {
            McbDevModeConfig.writeChoice(choice);
        }
        if (choice) {
            McbMod.activateDevMode();
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    @Override
    protected void init() {
        super.init();

        this.rememberBox = Checkbox.builder(REMEMBER, this.font)
                .pos(0, 0)
                .maxWidth(240)
                .selected(this.remember[0])
                .onValueChange((checkbox, value) -> this.remember[0] = value)
                .build();
        this.addRenderableWidget(this.rememberBox);
        this.repositionElements();
    }

    @Override
    protected void repositionElements() {
        super.repositionElements();
        if (this.rememberBox == null) return;
        int x = Math.max(0, (this.width - this.rememberBox.getWidth()) / 2);
        int y = this.layout.getY() + this.layout.getHeight() + 12;
        y = Math.min(y, this.height - this.rememberBox.getHeight() - 4);
        this.rememberBox.setPosition(x, Math.max(0, y));
    }
}
