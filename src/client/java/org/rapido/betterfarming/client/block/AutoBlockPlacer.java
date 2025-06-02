package org.rapido.betterfarming.client.block;

// import lib minecraft classes
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.block.Blocks;
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
    private static final List<BlockPos> blocksToPlace = new ArrayList<>();
    private static int currentBlockIndex = 0;
    private static final int PLACE_DELAY_TICKS = 2;
    private static int tickCounter = 0;
    private static final double MAX_REACH_DISTANCE = 4.5;
    private static int failureCounter = 0;
    private static final int MAX_FAILURES = 5;

    // Variables pour stocker la position virtuelle du joueur
    private static boolean isUsingVirtualPosition = false;
    private static Vec3d originalPlayerPos = null;
    private static Vec3d virtualPlayerPos = null;

    public static void selectFirstPoint(BlockPos pos) {
        firstPoint = pos;
        secondPoint = null;
        isPlacing = false;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal("§aPremier point sélectionné à : " + pos.toShortString()), true);
        }
    }

    public static void selectSecondPoint(BlockPos pos) {
        if (firstPoint != null) {
            secondPoint = pos;
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                client.player.sendMessage(Text.literal("§3Deuxième point sélectionné à : " + pos.toShortString()), true);
            }
        } else {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                client.player.sendMessage(Text.literal("§cVeuillez d'abord sélectionner le premier point (touche V)."), true);
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
                if (mainHandStack.isEmpty()) {
                    client.player.sendMessage(Text.literal("§cVous devez tenir un bloc ou des graines en main!"), true);
                    isPlacing = false;
                    return;
                }

                // Vérifier si c'est un bloc OU une graine
                boolean isValidItem = mainHandStack.getItem() instanceof BlockItem ||
                                     isPlantableItem(mainHandStack);

                if (!isValidItem) {
                    client.player.sendMessage(Text.literal("§cVous devez tenir un bloc ou des graines en main pour planter!"), true);
                    isPlacing = false;
                    return;
                }

                client.player.sendMessage(Text.literal("§eDébut de la construction automatique de " + blocksToPlace.size() + " éléments."), true);
            }
        } else {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                client.player.sendMessage(Text.literal("§cVeuillez sélectionner deux points avant de commencer la construction."), true);
            }
        }
    }

    /**
     * Vérifie si l'item est plantable (graines, etc.)
     */
    private static boolean isPlantableItem(ItemStack stack) {
        if (stack.isEmpty()) return false;

        // Liste des graines et autres items plantables
        return stack.getItem() == Items.WHEAT_SEEDS ||
               stack.getItem() == Items.BEETROOT_SEEDS ||
               stack.getItem() == Items.MELON_SEEDS ||
               stack.getItem() == Items.PUMPKIN_SEEDS ||
               stack.getItem() == Items.CARROT ||
               stack.getItem() == Items.POTATO;
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
        if (mainHandStack.isEmpty()) {
            player.sendMessage(Text.literal("§cPlacement interrompu: vous devez avoir un bloc ou des graines en main!"), true);
            isPlacing = false;
            return;
        }

        // Vérifier si l'item est valide (bloc OU graine)
        boolean isValidItem = mainHandStack.getItem() instanceof BlockItem ||
                             isPlantableItem(mainHandStack);

        if (!isValidItem) {
            player.sendMessage(Text.literal("§cPlacement interrompu: vous devez avoir un bloc ou des graines en main!"), true);
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
            player.sendMessage(Text.literal("§ePlacement automatique terminé."), true);
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
            resetVirtualPosition(player);
            return;
        }

        ItemStack mainHandStack = player.getMainHandStack();
        boolean isPlantable = isPlantableItem(mainHandStack);
        boolean isBlock = mainHandStack.getItem() instanceof BlockItem;

        if (!isPlantable && !isBlock) {
            isPlacing = false;
            player.sendMessage(Text.literal("§cPlacement interrompu: item non valide."), true);
            return;
        }

        BlockPos targetPos = blocksToPlace.get(currentBlockIndex);

        // Vérifier si le bloc existe déjà ou s'il y a déjà quelque chose à cette position
        if (!world.getBlockState(targetPos).isAir()) {
            currentBlockIndex++;
            failureCounter = 0;
            return;
        }

        // Vérifier si le joueur est assez proche du bloc à placer
        Vec3d playerPos = player.getEyePos();
        Vec3d blockCenter = Vec3d.ofCenter(targetPos);
        double distance = playerPos.distanceTo(blockCenter);

        // Si le joueur est trop loin, on crée une position virtuelle plus proche
        if (distance > MAX_REACH_DISTANCE) {
            // Calculer une position virtuelle plus proche du bloc
            if (!isUsingVirtualPosition) {
                originalPlayerPos = player.getPos();
                // Créer une position virtuelle à 3 blocs du bloc cible
                Vec3d direction = blockCenter.subtract(playerPos).normalize();
                double newDistance = Math.min(3.0, distance - 1.0);
                virtualPlayerPos = blockCenter.subtract(direction.multiply(newDistance));

                // Appliquer la position virtuelle
                setVirtualPosition(player, virtualPlayerPos);
                isUsingVirtualPosition = true;

                // On retourne pour que le tick suivant utilise la position virtuelle
                return;
            }
        } else if (isUsingVirtualPosition) {
            // Si on était en position virtuelle mais que la distance est bonne maintenant
            resetVirtualPosition(player);
        }

        // Si c'est une graine, on cherche spécifiquement des blocs de terre labourée en-dessous
        if (isPlantable) {
            BlockPos farmlandPos = targetPos.down();

            // Vérifier si le bloc en dessous est de la terre labourée
            if (world.getBlockState(farmlandPos).getBlock() == Blocks.FARMLAND) {
                // Configurer le clic sur la terre labourée avec une direction vers le haut
                Direction faceDirection = Direction.UP;

                // Position exacte pour le clic (centre du bloc de terre labourée, face supérieure)
                Vec3d hitVec = new Vec3d(farmlandPos.getX() + 0.5, farmlandPos.getY() + 1.0, farmlandPos.getZ() + 0.5);

                // Orienter le joueur vers la terre labourée
                Vec3d lookPos = Vec3d.ofCenter(farmlandPos);
                double dx = lookPos.x - playerPos.x;
                double dy = lookPos.y - playerPos.y;
                double dz = lookPos.z - playerPos.z;

                // Calculer les angles de vision
                double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
                float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                float pitch = (float) Math.toDegrees(-Math.atan2(dy, horizontalDistance));

                // Appliquer les angles
                float oldYaw = player.getYaw();
                float oldPitch = player.getPitch();
                player.setYaw(yaw);
                player.setPitch(pitch);
                player.setSneaking(false);

                // Créer le résultat du clic pour planter la graine
                BlockHitResult hitResult = new BlockHitResult(
                    hitVec,           // Position exacte du clic
                    faceDirection,    // Face UP (on clique sur le dessus de la terre labourée)
                    farmlandPos,      // Position de la terre labourée
                    false             // inside (toujours false pour un clic externe)
                );

                // Effectuer le placement avec swing hand
                player.swingHand(Hand.MAIN_HAND);
                ActionResult result = interactionManager.interactBlock(player, Hand.MAIN_HAND, hitResult);

                // Restaurer l'orientation du joueur
                player.setYaw(oldYaw);
                player.setPitch(oldPitch);

                if (result == ActionResult.SUCCESS) {
                    // Le placement a réussi
                    currentBlockIndex++;
                    failureCounter = 0;
                    resetVirtualPosition(player);

                    // Afficher la progression
                    if (currentBlockIndex % 10 == 0 || currentBlockIndex == blocksToPlace.size()) {
                        int progress = (currentBlockIndex * 100) / blocksToPlace.size();
                        player.sendMessage(Text.literal("Progrès: " + currentBlockIndex + "/" + blocksToPlace.size() + " (" + progress + "%)"), true);
                    }
                } else {
                    // Le placement a échoué
                    handlePlacementFailure(player, targetPos);
                }
                return; // Important : ne pas continuer avec le code normal pour les blocs
            } else {
                // Pas de terre labourée sous la position cible, passer à la position suivante
                currentBlockIndex++;
                return;
            }
        }

        // Code pour le placement de blocs normaux (reste inchangé)
        Direction bestDirection = null;
        BlockPos bestSupportPos = null;

        // Prioriser les directions dans un ordre spécifique pour maximiser les chances de succès
        Direction[] directions = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};

        for (Direction dir : directions) {
            BlockPos adjacentPos = targetPos.offset(dir);
            if (!world.getBlockState(adjacentPos).isAir() && world.getBlockState(adjacentPos).isSolidBlock(world, adjacentPos)) {
                bestDirection = dir;
                bestSupportPos = adjacentPos;
                break;
            }
        }

        if (bestDirection == null || bestSupportPos == null) {
            // Aucun bloc support trouvé, passer au suivant
            currentBlockIndex++;
            return;
        }

        // Orienter le joueur vers le bloc support
        Vec3d lookPos = Vec3d.ofCenter(bestSupportPos);
        double dx = lookPos.x - playerPos.x;
        double dy = lookPos.y - playerPos.y;
        double dz = lookPos.z - playerPos.z;

        // Calculer les angles de vision
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, horizontalDistance));

        // Appliquer les angles
        float oldYaw = player.getYaw();
        float oldPitch = player.getPitch();
        player.setYaw(yaw);
        player.setPitch(pitch);
        player.setSneaking(false);

        // Direction et point pour le clic
        Direction faceDirection = bestDirection.getOpposite();
        Vec3d hitVec;

        switch (faceDirection) {
            case DOWN:  hitVec = new Vec3d(bestSupportPos.getX() + 0.5, bestSupportPos.getY(), bestSupportPos.getZ() + 0.5); break;
            case UP:    hitVec = new Vec3d(bestSupportPos.getX() + 0.5, bestSupportPos.getY() + 1.0, bestSupportPos.getZ() + 0.5); break;
            case NORTH: hitVec = new Vec3d(bestSupportPos.getX() + 0.5, bestSupportPos.getY() + 0.5, bestSupportPos.getZ()); break;
            case SOUTH: hitVec = new Vec3d(bestSupportPos.getX() + 0.5, bestSupportPos.getY() + 0.5, bestSupportPos.getZ() + 1.0); break;
            case WEST:  hitVec = new Vec3d(bestSupportPos.getX(), bestSupportPos.getY() + 0.5, bestSupportPos.getZ() + 0.5); break;
            case EAST:  hitVec = new Vec3d(bestSupportPos.getX() + 1.0, bestSupportPos.getY() + 0.5, bestSupportPos.getZ() + 0.5); break;
            default:    hitVec = Vec3d.ofCenter(bestSupportPos); break;
        }

        // Créer le résultat du clic
        BlockHitResult hitResult = new BlockHitResult(
            hitVec,
            faceDirection,
            bestSupportPos,
            false
        );

        // Effectuer le placement
        player.swingHand(Hand.MAIN_HAND);
        ActionResult result = interactionManager.interactBlock(player, Hand.MAIN_HAND, hitResult);

        // Restaurer l'orientation du joueur
        player.setYaw(oldYaw);
        player.setPitch(oldPitch);

        if (result == ActionResult.SUCCESS) {
            // Le placement a réussi
            currentBlockIndex++;
            failureCounter = 0;
            resetVirtualPosition(player);

            // Afficher la progression
            if (currentBlockIndex % 10 == 0 || currentBlockIndex == blocksToPlace.size()) {
                int progress = (currentBlockIndex * 100) / blocksToPlace.size();
                client.player.sendMessage(Text.literal("Progrès: " + currentBlockIndex + "/" + blocksToPlace.size() + " (" + progress + "%)"), true);
            }
        } else {
            // Le placement a échoué
            handlePlacementFailure(player, targetPos);
        }
    }

    // Méthode pour gérer les échecs de placement
    private static void handlePlacementFailure(ClientPlayerEntity player, BlockPos targetPos) {
        failureCounter++;
        if (failureCounter > MAX_FAILURES) {
            failureCounter = 0;
            currentBlockIndex++;
            resetVirtualPosition(player);
        }
    }

    // Méthode pour appliquer une position virtuelle au joueur
    private static void setVirtualPosition(ClientPlayerEntity player, Vec3d pos) {
        if (player == null) return;

        // Sauvegarder la position originale si ce n'est pas déjà fait
        if (originalPlayerPos == null) {
            originalPlayerPos = player.getPos();
        }

        // Appliquer la position virtuelle
        player.setPos(pos.x, pos.y, pos.z);
        isUsingVirtualPosition = true;
    }

    // Méthode pour rétablir la position réelle du joueur
    private static void resetVirtualPosition(ClientPlayerEntity player) {
        if (player == null || !isUsingVirtualPosition || originalPlayerPos == null) return;

        // Restaurer la position originale
        player.setPos(originalPlayerPos.x, originalPlayerPos.y, originalPlayerPos.z);

        // Réinitialiser les variables
        isUsingVirtualPosition = false;
        originalPlayerPos = null;
        virtualPlayerPos = null;
    }

    private static void calculateBlocksToPlace() {
        blocksToPlace.clear();

        int minX = Math.min(firstPoint.getX(), secondPoint.getX());
        int minY = Math.min(firstPoint.getY(), secondPoint.getY());
        int minZ = Math.min(firstPoint.getZ(), secondPoint.getZ());

        int maxX = Math.max(firstPoint.getX(), secondPoint.getX());
        int maxY = Math.max(firstPoint.getY(), secondPoint.getY());
        int maxZ = Math.max(firstPoint.getZ(), secondPoint.getZ());

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal("Début de la pose des blocks"), true);
        }

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    blocksToPlace.add(new BlockPos(x, y, z)); // Pas de correction de hauteur
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
                // Rétablir la position réelle du joueur
                resetVirtualPosition(client.player);
                client.player.sendMessage(Text.literal("Construction interrompue par l'utilisateur."), true);
            }
        }
    }
}

