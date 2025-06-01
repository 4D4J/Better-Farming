package org.rapido.betterfarming.client.block;

// import lib minecraft classes
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

public class AutoBlockPlacer {
    private static BlockPos firstPoint = null;
    private static BlockPos secondPoint = null;
    private static boolean isPlacing = false;
    private static List<BlockPos> blocksToPlace = new ArrayList<>();
    private static int currentBlockIndex = 0;
    private static final int PLACE_DELAY_TICKS = 2; 
    private static int tickCounter = 0;
    private static final double MAX_REACH_DISTANCE = 4.5;
    private static int failureCounter = 0;
    private static final int MAX_FAILURES = 5;

    public static void selectFirstPoint(BlockPos pos) {
        firstPoint = pos;
        secondPoint = null;
        isPlacing = false;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal("Premier point sélectionné à : " + pos.toShortString()), true);
        }
    }

    public static void selectSecondPoint(BlockPos pos) {
        if (firstPoint != null) {
            secondPoint = pos;
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                client.player.sendMessage(Text.literal("Deuxième point sélectionné à : " + pos.toShortString()), true);
            }
        } else {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                client.player.sendMessage(Text.literal("Veuillez d'abord sélectionner le premier point (touche V)."), true);
            }
        }
    }

    public static void startPlacing() {
        if (firstPoint != null && secondPoint != null) {
            calculateBlocksToPlace();
            isPlacing = true;
            currentBlockIndex = 0;
            tickCounter = 0;
            failureCounter = 0;

            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                ItemStack mainHandStack = client.player.getMainHandStack();
                if (mainHandStack.isEmpty() || !(mainHandStack.getItem() instanceof BlockItem)) {
                    client.player.sendMessage(Text.literal("Vous devez tenir un bloc en main pour construire!"), true);
                    isPlacing = false;
                    return;
                }
                client.player.sendMessage(Text.literal("Début de la construction automatique de " + blocksToPlace.size() + " blocs."), true);
            }
        } else {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                client.player.sendMessage(Text.literal("Veuillez sélectionner deux points avant de commencer la construction."), true);
            }
        }
    }

    public static void tick() {
        if (!isPlacing || blocksToPlace.isEmpty()) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;

        if (player == null) {
            isPlacing = false;
            return;
        }
        
        ItemStack mainHandStack = player.getMainHandStack();
        if (mainHandStack.isEmpty() || !(mainHandStack.getItem() instanceof BlockItem)) {
            player.sendMessage(Text.literal("Construction interrompue: vous devez avoir un bloc en main!"), true);
            isPlacing = false;
            return;
        }

        tickCounter++;
        if (tickCounter < PLACE_DELAY_TICKS) {
            return;
        }
        tickCounter = 0;
        
        if (currentBlockIndex >= blocksToPlace.size()) {
            isPlacing = false;
            player.sendMessage(Text.literal("Construction automatique terminée."), true);
            return;
        }

        placeNextBlock();
    }

    private static void placeNextBlock() {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        ClientPlayerInteractionManager interactionManager = client.interactionManager;

        if (player == null || world == null || interactionManager == null || currentBlockIndex >= blocksToPlace.size()) {
            isPlacing = false;
            return;
        }

        BlockPos pos = blocksToPlace.get(currentBlockIndex);

        Vec3d playerPos = player.getEyePos();
        Vec3d blockCenter = Vec3d.ofCenter(pos);
        double distance = playerPos.distanceTo(blockCenter);

        if (distance > MAX_REACH_DISTANCE) {
            failureCounter++;
            if (failureCounter > MAX_FAILURES) {
                failureCounter = 0;
                currentBlockIndex++;
                player.sendMessage(Text.literal("Bloc ignoré à " + pos.toShortString() + " (trop loin)"), true);
            }
            return;
        }

        if (!world.getBlockState(pos).isAir()) {
            currentBlockIndex++;
            failureCounter = 0;
            return;
        }

        Direction placementDirection = null;
        for (Direction direction : Direction.values()) {
            BlockPos adjacent = pos.offset(direction);
            if (!world.getBlockState(adjacent).isAir()) {
                placementDirection = direction.getOpposite();
                break;
            }
        }


        if (placementDirection == null) {
            currentBlockIndex++;
            failureCounter = 0;
            return;
        }

        // Créer un BlockHitResult précis pour le placement
        BlockPos targetPos = pos.offset(placementDirection);
        Vec3d hitVec = Vec3d.ofCenter(targetPos).add(
            placementDirection.getOffsetX() * 0.5,
            placementDirection.getOffsetY() * 0.5,
            placementDirection.getOffsetZ() * 0.5
        );

        BlockHitResult hitResult = new BlockHitResult(
            hitVec,
            placementDirection.getOpposite(),
            targetPos,
            false
        );


        ActionResult result = interactionManager.interactBlock(player, Hand.MAIN_HAND, hitResult);

        if (result == ActionResult.SUCCESS) {
            currentBlockIndex++;
            failureCounter = 0;

            if (currentBlockIndex % 10 == 0 || currentBlockIndex == blocksToPlace.size()) {
                int progress = (currentBlockIndex * 100) / blocksToPlace.size();
                player.sendMessage(Text.literal("Progrès: " + currentBlockIndex + "/" + blocksToPlace.size() + " (" + progress + "%)"), true);
            }
        } else {
            failureCounter++;
            if (failureCounter > MAX_FAILURES) {
                failureCounter = 0;
                currentBlockIndex++; // Passer au bloc suivant après plusieurs échecs
                player.sendMessage(Text.literal("Impossible de placer le bloc à " + pos.toShortString() + ", passage au suivant"), true);
            }
        }
    }

    private static void calculateBlocksToPlace() {
        blocksToPlace.clear();

        int minX = Math.min(firstPoint.getX(), secondPoint.getX());
        int minY = Math.min(firstPoint.getY(), secondPoint.getY());
        int minZ = Math.min(firstPoint.getZ(), secondPoint.getZ());

        int maxX = Math.max(firstPoint.getX(), secondPoint.getX());
        int maxY = Math.max(firstPoint.getY(), secondPoint.getY());
        int maxZ = Math.max(firstPoint.getZ(), secondPoint.getZ());

        int heightCorrection = -1;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal("Début de la construction avec correction de hauteur: " + heightCorrection), false);
        }

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    blocksToPlace.add(new BlockPos(x, y + heightCorrection, z));
                }
            }
        }
    }

    public static boolean hasSelectedFirstPoint() {
        return firstPoint != null;
    }

    public static boolean hasSelectedSecondPoint() {
        return secondPoint != null;
    }

    public static boolean isPlacing() {
        return isPlacing;
    }

    public static void stopPlacing() {
        if (isPlacing) {
            isPlacing = false;
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                client.player.sendMessage(Text.literal("Construction interrompue par l'utilisateur."), true);
            }
        }
    }
}

