package org.rapido.betterfarming.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import org.lwjgl.glfw.GLFW;
import org.rapido.betterfarming.client.block.AutoBlockPlacer;

public class BetterfarmingClient implements ClientModInitializer {
    private static KeyBinding keyBindFirstPoint;
    private static KeyBinding keyBindSecondPoint;
    private static KeyBinding keyBindStartPlacing;

    @Override
    public void onInitializeClient() {

        // KeyBinds pour le AutoBlockPlacer
        keyBindFirstPoint = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "betterfarming.select_first_point",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                "category.betterfarming.building"
        ));
        keyBindSecondPoint = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "betterfarming.select_second_point",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_B,
                "category.betterfarming.building"
        ));
        keyBindStartPlacing = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "betterfarming.start_placing",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_N,
                "category.betterfarming.building"
        ));



        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (keyBindFirstPoint.wasPressed()) {
                handleFirstPointSelection(client);
            }

            if (keyBindSecondPoint.wasPressed()) {
                handleSecondPointSelection(client);
            }

            if (keyBindStartPlacing.wasPressed()) {
                handleStartPlacing(client);
            }

            AutoBlockPlacer.tick();
        });
    }

    private void handleFirstPointSelection(MinecraftClient client) {
        if (client.player != null && client.crosshairTarget != null && client.crosshairTarget.getType() == HitResult.Type.BLOCK) {
            BlockHitResult blockHit = (BlockHitResult) client.crosshairTarget;
            AutoBlockPlacer.selectFirstPoint(blockHit.getBlockPos());
            client.player.sendMessage(Text.literal("Premier point sélectionné à " + blockHit.getBlockPos().toShortString()), true);
        }
    }

    private void handleSecondPointSelection(MinecraftClient client) {
        if (client.player != null && client.crosshairTarget != null && client.crosshairTarget.getType() == HitResult.Type.BLOCK) {
            BlockHitResult blockHit = (BlockHitResult) client.crosshairTarget;
            AutoBlockPlacer.selectSecondPoint(blockHit.getBlockPos());
            client.player.sendMessage(Text.literal("Deuxième point sélectionné à " + blockHit.getBlockPos().toShortString()), true);
        }
    }

    private void handleStartPlacing(MinecraftClient client) {
        if (client.player != null) {
            if (!AutoBlockPlacer.hasSelectedFirstPoint()) {
                client.player.sendMessage(Text.literal("Veuillez d'abord sélectionner le premier point (touche V)"), true);
                return;
            }
            if (!AutoBlockPlacer.hasSelectedSecondPoint()) {
                client.player.sendMessage(Text.literal("Veuillez d'abord sélectionner le deuxième point (touche B)"), true);
                return;
            }
            AutoBlockPlacer.startPlacing();
            client.player.sendMessage(Text.literal("Construction automatique démarrée!"), true);
        }
    }
}
