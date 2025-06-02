package org.rapido.betterfarming.client.TreeCutter;

import net.minecraft.client.MinecraftClient;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Direction;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class AutoTreeCutter {

    private static final int SEARCH_RADIUS = 20;
    private static final int MAX_TREE_HEIGHT = 30;
    private static final int MIN_TREE_HEIGHT = 3;

    private static final Set<Block> WOOD_BLOCKS = Set.of(
            Blocks.OAK_LOG, Blocks.BIRCH_LOG, Blocks.SPRUCE_LOG,
            Blocks.JUNGLE_LOG, Blocks.ACACIA_LOG, Blocks.DARK_OAK_LOG,
            Blocks.MANGROVE_LOG, Blocks.CHERRY_LOG, Blocks.CRIMSON_STEM,
            Blocks.WARPED_STEM, Blocks.STRIPPED_OAK_LOG, Blocks.STRIPPED_BIRCH_LOG,
            Blocks.STRIPPED_SPRUCE_LOG, Blocks.STRIPPED_JUNGLE_LOG,
            Blocks.STRIPPED_ACACIA_LOG, Blocks.STRIPPED_DARK_OAK_LOG,
            Blocks.MANGROVE_ROOTS, Blocks.MUDDY_MANGROVE_ROOTS
    );

    private static final Set<Block> LEAF_BLOCKS = Set.of(
            Blocks.OAK_LEAVES, Blocks.BIRCH_LEAVES, Blocks.SPRUCE_LEAVES,
            Blocks.JUNGLE_LEAVES, Blocks.ACACIA_LEAVES, Blocks.DARK_OAK_LEAVES,
            Blocks.MANGROVE_LEAVES, Blocks.CHERRY_LEAVES, Blocks.NETHER_WART_BLOCK,
            Blocks.WARPED_WART_BLOCK, Blocks.MANGROVE_PROPAGULE
    );

    private static final Set<Block> GROUND_BLOCKS = Set.of(
            Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.PODZOL,
            Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT, Blocks.MYCELIUM,
            Blocks.SAND, Blocks.RED_SAND, Blocks.SOUL_SAND,
            Blocks.SOUL_SOIL, Blocks.NETHERRACK, Blocks.CRIMSON_NYLIUM,
            Blocks.WARPED_NYLIUM, Blocks.MUD, Blocks.MUDDY_MANGROVE_ROOTS
    );

    private static boolean isRunning = false;

    public static void cutTree() {
        if (isRunning) {
            sendMessage("§cCoupe d'arbres déjà en cours!", Formatting.RED);
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return;

        // Exécuter de manière asynchrone pour ne pas bloquer le jeu
        CompletableFuture.runAsync(() -> {
            isRunning = true;
            try {
                cutTreesAsync(client);
            } finally {
                isRunning = false;
            }
        });
    }

    private static void cutTreesAsync(MinecraftClient client) {
        PlayerEntity player = client.player;
        World world = client.world;
        if (player == null || world == null) return;

        BlockPos playerPos = player.getBlockPos();

        sendMessage("§aDétection des arbres...", Formatting.GREEN);

        // Rechercher tous les arbres dans le périmètre (optimisé)
        List<TreeData> trees = findTreesOptimized(world, playerPos, SEARCH_RADIUS);

        if (trees.isEmpty()) {
            sendMessage("§eAucun arbre trouvé dans un rayon de " + SEARCH_RADIUS + " blocs", Formatting.YELLOW);
            return;
        }

        sendMessage("§a" + trees.size() + " arbre(s) détecté(s). Début de la coupe...", Formatting.GREEN);

        // Sauvegarder la position originale du joueur
        Vec3d originalPos = player.getPos();
        float originalYaw = player.getYaw();
        float originalPitch = player.getPitch();

        try {
            // Traiter chaque arbre
            for (int i = 0; i < trees.size(); i++) {
                TreeData tree = trees.get(i);

                sendMessage("§bCoupe de l'arbre " + (i + 1) + "/" + trees.size() + " (Hauteur: " + tree.height + " blocs)", Formatting.AQUA);

                // Se déplacer virtuellement devant l'arbre
                moveToTreeOptimized(client, tree.root);

                // Attendre synchronisation
                sleep(30);

                // Casser tout l'arbre en commençant par la racine
                breakEntireTree(client, tree);

                // Attendre que l'arbre tombe complètement
                sleep(50);
            }

            sendMessage("§a✓ Coupe terminée! " + trees.size() + " arbre(s) abattu(s)", Formatting.GREEN);

        } finally {
            // Retourner à la position originale
            sleep(100);
            teleportPlayer(client, originalPos, originalYaw, originalPitch);
            sendMessage("§7Retour à la position d'origine", Formatting.GRAY);
        }
    }

    private static List<TreeData> findTreesOptimized(World world, BlockPos center, int radius) {
        List<TreeData> trees = new ArrayList<>();
        Set<BlockPos> processedPositions = new HashSet<>();

        // Optimisation: parcourir de bas en haut pour détecter les racines plus rapidement
        for (int y = -5; y <= 15; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    // Optimisation: distance euclidienne pour un rayon plus précis
                    if (x * x + z * z > radius * radius) continue;

                    BlockPos pos = center.add(x, y, z);

                    if (processedPositions.contains(pos)) continue;

                    BlockState state = world.getBlockState(pos);
                    if (WOOD_BLOCKS.contains(state.getBlock())) {
                        TreeData tree = analyzeTree(world, pos);
                        if (tree != null && tree.height >= MIN_TREE_HEIGHT &&
                                !processedPositions.contains(tree.root)) {
                            trees.add(tree);
                            // Marquer tout l'arbre comme traité
                            tree.allWoodBlocks.forEach(processedPositions::add);
                        }
                    }
                }
            }
        }

        return trees;
    }

    private static TreeData analyzeTree(World world, BlockPos startPos) {
        // Trouver la racine de l'arbre
        BlockPos root = findTreeRoot(world, startPos);
        if (root == null) return null;

        // Analyser tout l'arbre
        Set<BlockPos> woodBlocks = new HashSet<>();
        Set<BlockPos> leafBlocks = new HashSet<>();

        Queue<BlockPos> queue = new LinkedList<>();
        Set<BlockPos> visited = new HashSet<>();
        queue.add(root);

        int maxHeight = 0;

        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            if (visited.contains(pos)) continue;
            visited.add(pos);

            BlockState state = world.getBlockState(pos);
            Block block = state.getBlock();

            if (WOOD_BLOCKS.contains(block)) {
                woodBlocks.add(pos);
                maxHeight = Math.max(maxHeight, pos.getY() - root.getY());

                // Chercher les blocs adjacents (optimisé: seulement 6 directions principales)
                for (Direction dir : Direction.values()) {
                    BlockPos adjacent = pos.offset(dir);
                    if (!visited.contains(adjacent) &&
                            (adjacent.getY() - root.getY()) <= MAX_TREE_HEIGHT) {
                        queue.add(adjacent);
                    }
                }
            } else if (LEAF_BLOCKS.contains(block)) {
                leafBlocks.add(pos);
                // Les feuilles peuvent aussi connecter des parties d'arbre
                for (Direction dir : Direction.values()) {
                    BlockPos adjacent = pos.offset(dir);
                    if (!visited.contains(adjacent) &&
                            (adjacent.getY() - root.getY()) <= MAX_TREE_HEIGHT) {
                        BlockState adjState = world.getBlockState(adjacent);
                        if (WOOD_BLOCKS.contains(adjState.getBlock())) {
                            queue.add(adjacent);
                        }
                    }
                }
            }
        }

        return woodBlocks.size() >= MIN_TREE_HEIGHT ?
                new TreeData(root, woodBlocks, leafBlocks, maxHeight + 1) : null;
    }

    private static BlockPos findTreeRoot(World world, BlockPos logPos) {
        BlockPos current = logPos;
        BlockPos root = null;

        // Descendre jusqu'à la base
        while (current.getY() > world.getBottomY()) {
            BlockState state = world.getBlockState(current);
            if (WOOD_BLOCKS.contains(state.getBlock())) {
                root = current;
                current = current.down();
            } else {
                break;
            }
        }

        // Vérifier le sol
        if (root != null) {
            BlockState groundState = world.getBlockState(root.down());
            if (GROUND_BLOCKS.contains(groundState.getBlock())) {
                return root;
            }
        }

        return null;
    }

    private static void moveToTreeOptimized(MinecraftClient client, BlockPos treePos) {
        if (client.player == null) return;

        // Position optimisée: chercher un endroit accessible autour de l'arbre
        Vec3d bestPos = findBestPositionAroundTree(client.world, treePos);

        // Téléporter le joueur
        teleportPlayer(client, bestPos, 0, 0);
    }

    private static Vec3d findBestPositionAroundTree(World world, BlockPos treePos) {
        // Essayer différentes positions autour de l'arbre
        int[] offsets = {2, 3, 1, 4}; // Priorité aux positions proches

        for (int offset : offsets) {
            for (int angle = 0; angle < 360; angle += 45) {
                double rad = Math.toRadians(angle);
                double x = treePos.getX() + 0.5 + Math.cos(rad) * offset;
                double z = treePos.getZ() + 0.5 + Math.sin(rad) * offset;
                double y = treePos.getY();

                // Vérifier si la position est accessible
                BlockPos checkPos = new BlockPos((int)x, (int)y, (int)z);
                if (isPositionSafe(world, checkPos)) {
                    return new Vec3d(x, y, z);
                }
            }
        }

        // Position par défaut
        return new Vec3d(treePos.getX() + 2.5, treePos.getY(), treePos.getZ() + 0.5);
    }

    private static boolean isPositionSafe(World world, BlockPos pos) {
        // Vérifier que les pieds et la tête sont libres
        return world.getBlockState(pos).isAir() &&
                world.getBlockState(pos.up()).isAir() &&
                !world.getBlockState(pos.down()).isAir();
    }

    private static void breakEntireTree(MinecraftClient client, TreeData tree) {
        if (client.player == null || client.getNetworkHandler() == null) return;

        // Commencer par la racine pour déclencher la chute
        breakBlock(client, tree.root);
        sleep(20);

        // Casser tous les autres blocs de bois rapidement
        List<BlockPos> sortedWood = new ArrayList<>(tree.allWoodBlocks);
        sortedWood.remove(tree.root); // Déjà cassé

        // Trier par hauteur (de bas en haut)
        sortedWood.sort(Comparator.comparingInt(BlockPos::getY));

        for (BlockPos pos : sortedWood) {
            breakBlock(client, pos);
            sleep(5); // Délai minimal entre chaque bloc
        }

        // Casser quelques feuilles pour faire plus naturel
        List<BlockPos> leafList = new ArrayList<>(tree.leafBlocks);
        Collections.shuffle(leafList);
        int leavesToBreak = Math.min(leafList.size() / 3, 10);

        for (int i = 0; i < leavesToBreak; i++) {
            breakBlock(client, leafList.get(i));
            sleep(3);
        }
    }

    private static void teleportPlayer(MinecraftClient client, Vec3d pos, float yaw, float pitch) {
        if (client.player == null || client.getNetworkHandler() == null) return;

        // Packet de téléportation optimisé
        client.getNetworkHandler().sendPacket(
                new PlayerMoveC2SPacket.PositionAndOnGround(pos.x, pos.y, pos.z, true, true)
        );

        // Mise à jour locale
        client.player.setPos(pos.x, pos.y, pos.z);
        client.player.setYaw(yaw);
        client.player.setPitch(pitch);
    }

    private static void breakBlock(MinecraftClient client, BlockPos pos) {
        if (client.player == null || client.getNetworkHandler() == null) return;

        // Optimisation: actions combinées
        client.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, pos, Direction.UP
        ));

        client.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, pos, Direction.UP
        ));

        // Interaction locale
        if (client.interactionManager != null) {
            client.interactionManager.attackBlock(pos, Direction.UP);
            client.player.swingHand(Hand.MAIN_HAND);
        }
    }

    private static void sendMessage(String message, Formatting color) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal("[TreeCutter] " + message).formatted(color), false);
        }
    }

    private static void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // Classe pour stocker les données d'un arbre
    private static class TreeData {
        final BlockPos root;
        final Set<BlockPos> allWoodBlocks;
        final Set<BlockPos> leafBlocks;
        final int height;

        TreeData(BlockPos root, Set<BlockPos> woodBlocks, Set<BlockPos> leafBlocks, int height) {
            this.root = root;
            this.allWoodBlocks = woodBlocks;
            this.leafBlocks = leafBlocks;
            this.height = height;
        }
    }
}

// debug la classe de TreeCutter, rien ne va