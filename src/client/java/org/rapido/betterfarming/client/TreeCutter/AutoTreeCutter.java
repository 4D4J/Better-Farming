package org.rapido.betterfarming.client.TreeCutter;

import net.minecraft.client.MinecraftClient;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Direction;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundEvents;
import net.minecraft.sound.SoundCategory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public class AutoTreeCutter {

    // Configuration générale
    private static final int SEARCH_RADIUS = 20;
    private static final int MAX_TREE_HEIGHT = 30;
    private static final int MIN_TREE_HEIGHT = 3;
    private static final int MAX_TREES_PER_RUN = 10;

    // Délai minimal entre les actions
    private static final int TELEPORT_DELAY = 50;

    // État de fonctionnement
    private static final AtomicBoolean isRunning = new AtomicBoolean(false);

    // Types de blocs
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


    public static void cutTree() {
        if (isRunning.get()) {
            sendActionBarMessage("Coupe d'arbres déjà en cours!", Formatting.RED);
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.world == null) {
            sendActionBarMessage("Impossible de démarrer la coupe (client non initialisé)", Formatting.RED);
            return;
        }

        CompletableFuture.runAsync(() -> {
            if (!isRunning.compareAndSet(false, true)) {
                return;
            }

            try {
                processTreeCutting(client);
            } catch (Exception e) {
                sendActionBarMessage("Erreur: " + e.getMessage(), Formatting.RED);
                e.printStackTrace();
            } finally {
                isRunning.set(false);
            }
        });
    }


    private static void processTreeCutting(MinecraftClient client) {
        PlayerEntity player = client.player;
        World world = client.world;
        if (player == null || world == null) return;

        BlockPos playerPos = player.getBlockPos();
        Vec3d originalPos = player.getPos();
        float originalYaw = player.getYaw();
        float originalPitch = player.getPitch();

        sendActionBarMessage("Recherche des arbres...", Formatting.GREEN);

        // Détection des arbres optimisée
        List<TreeData> trees = findTrees(world, playerPos, SEARCH_RADIUS);

        if (trees.isEmpty()) {
            sendActionBarMessage("Aucun arbre trouvé dans un rayon de " + SEARCH_RADIUS + " blocs", Formatting.YELLOW);
            return;
        }

        // Limiter le nombre d'arbres pour éviter de surcharger le client
        int treesToProcess = Math.min(trees.size(), MAX_TREES_PER_RUN);
        sendActionBarMessage(treesToProcess + " arbre(s) détecté(s) sur " + trees.size() + ". Début de la coupe...", Formatting.GREEN);

        try {
            // Trier les arbres par proximité
            trees.sort(Comparator.comparingDouble(tree ->
                    tree.root.getSquaredDistance(playerPos)));

            // Couper chaque arbre
            for (int i = 0; i < treesToProcess; i++) {
                TreeData tree = trees.get(i);

                sendActionBarMessage("Arbre " + (i+1) + "/" + treesToProcess + " (taille: " + tree.height + ")", Formatting.AQUA);

                // Se positionner près de l'arbre
                movePlayerToTree(client, tree.root);
                sleep(TELEPORT_DELAY);

                // Couper l'arbre (casser la racine et faire dropper le reste)
                harvestTreeByRoot(client, tree);

                // Attendre un peu entre chaque arbre
                sleep(200);

                // Vérifier si l'utilisateur a interrompu le processus
                if (!isRunning.get()) {
                    sendActionBarMessage("Coupe interrompue par l'utilisateur", Formatting.YELLOW);
                    break;
                }
            }

            sendActionBarMessage("✓ Coupe terminée! " + treesToProcess + " arbre(s) abattu(s)", Formatting.GREEN);

        } finally {
            // Retour à la position d'origine
            sleep(100);
            teleportPlayer(client, originalPos, originalYaw, originalPitch);
            sendActionBarMessage("Retour à la position d'origine", Formatting.GRAY);
        }
    }


    private static List<TreeData> findTrees(World world, BlockPos center, int radius) {
        List<TreeData> trees = new ArrayList<>();
        Set<BlockPos> processedRoots = new HashSet<>();

        // Scan optimisé par couches
        for (int y = -3; y <= 5; y++) { // Commencer par les hauteurs où les racines sont probables
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    // Vérifier si on est dans le rayon (optimisation)
                    if (x*x + z*z > radius*radius) continue;

                    BlockPos pos = center.add(x, y, z);

                    // Vérifier si c'est un bloc de bois
                    BlockState state = world.getBlockState(pos);
                    if (WOOD_BLOCKS.contains(state.getBlock())) {
                        // Trouver la racine de l'arbre
                        BlockPos root = findTreeRoot(world, pos);

                        // Si on a déjà traité cet arbre, passer au suivant
                        if (root == null || processedRoots.contains(root)) continue;
                        processedRoots.add(root);

                        // Analyser l'arbre
                        TreeData tree = analyzeTree(world, root);
                        if (tree != null && tree.height >= MIN_TREE_HEIGHT) {
                            trees.add(tree);
                        }
                    }
                }
            }
        }

        return trees;
    }


    private static BlockPos findTreeRoot(World world, BlockPos start) {
        BlockPos current = start;
        BlockPos lastWood = null;

        // Descendre jusqu'à trouver le sol
        while (current.getY() > world.getBottomY()) {
            BlockState state = world.getBlockState(current);
            if (WOOD_BLOCKS.contains(state.getBlock())) {
                lastWood = current;
                current = current.down();
            } else {
                break;
            }
        }

        // Vérifier si on a trouvé une racine valide
        if (lastWood != null) {
            BlockState ground = world.getBlockState(lastWood.down());
            if (GROUND_BLOCKS.contains(ground.getBlock())) {
                return lastWood;
            }
        }

        return null;
    }


    private static TreeData analyzeTree(World world, BlockPos root) {
        Set<BlockPos> woodBlocks = new HashSet<>();
        Set<BlockPos> leafBlocks = new HashSet<>();

        Queue<BlockPos> toExplore = new LinkedList<>();
        Set<BlockPos> visited = new HashSet<>();

        toExplore.add(root);
        int maxHeight = 0;

        // Algorithme BFS pour explorer l'arbre
        while (!toExplore.isEmpty()) {
            BlockPos pos = toExplore.poll();

            if (visited.contains(pos) ||
                    pos.getY() - root.getY() > MAX_TREE_HEIGHT ||
                    pos.getSquaredDistance(root) > MAX_TREE_HEIGHT * MAX_TREE_HEIGHT) {
                continue;
            }

            visited.add(pos);

            BlockState state = world.getBlockState(pos);
            Block block = state.getBlock();

            if (WOOD_BLOCKS.contains(block)) {
                woodBlocks.add(pos);
                maxHeight = Math.max(maxHeight, pos.getY() - root.getY());

                // Explorer dans toutes les directions
                for (Direction dir : Direction.values()) {
                    BlockPos next = pos.offset(dir);
                    if (!visited.contains(next)) {
                        toExplore.add(next);
                    }
                }

                // Vérifier les diagonales pour les arbres complexes
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if (dx == 0 && dy == 0 && dz == 0) continue;
                            BlockPos diagonal = pos.add(dx, dy, dz);
                            if (!visited.contains(diagonal)) {
                                BlockState diagState = world.getBlockState(diagonal);
                                if (WOOD_BLOCKS.contains(diagState.getBlock())) {
                                    toExplore.add(diagonal);
                                }
                            }
                        }
                    }
                }
            } else if (LEAF_BLOCKS.contains(block)) {
                leafBlocks.add(pos);

                // Les feuilles peuvent connecter des parties d'arbre
                for (Direction dir : Direction.values()) {
                    BlockPos next = pos.offset(dir);
                    if (!visited.contains(next)) {
                        BlockState nextState = world.getBlockState(next);
                        if (WOOD_BLOCKS.contains(nextState.getBlock())) {
                            toExplore.add(next);
                        }
                    }
                }
            }
        }

        // Vérifier si c'est un arbre valide
        if (woodBlocks.size() < MIN_TREE_HEIGHT) {
            return null;
        }

        return new TreeData(root, woodBlocks, leafBlocks, maxHeight + 1);
    }


    private static void movePlayerToTree(MinecraftClient client, BlockPos treePos) {
        if (client.player == null || client.world == null) return;

        // Trouver la meilleure position autour de l'arbre
        Vec3d bestPos = findSafePositionNearTree(client.world, treePos);

        // Se téléporter à cette position
        teleportPlayer(client, bestPos, client.player.getYaw(), client.player.getPitch());
    }


    private static Vec3d findSafePositionNearTree(World world, BlockPos treePos) {
        // Essayer d'abord les positions les plus proches
        for (int distance = 2; distance <= 4; distance++) {
            for (int angle = 0; angle < 360; angle += 30) {
                double rad = Math.toRadians(angle);
                double x = treePos.getX() + 0.5 + Math.cos(rad) * distance;
                double z = treePos.getZ() + 0.5 + Math.sin(rad) * distance;

                // Chercher une position où les pieds sont sur un bloc solide
                BlockPos checkPos = new BlockPos((int)x, treePos.getY(), (int)z);

                // Trouver le sol
                while (world.getBlockState(checkPos).isAir() && checkPos.getY() > world.getBottomY()) {
                    checkPos = checkPos.down();
                }

                // Monter d'un bloc pour être sur le sol
                checkPos = checkPos.up();

                if (isSafePosition(world, checkPos)) {
                    return new Vec3d(x, checkPos.getY(), z);
                }
            }
        }

        // Position par défaut si aucune position sûre n'est trouvée
        return new Vec3d(treePos.getX() + 3, treePos.getY(), treePos.getZ());
    }


    private static boolean isSafePosition(World world, BlockPos pos) {
        return world.getBlockState(pos).isAir() &&
                world.getBlockState(pos.up()).isAir() &&
                !world.getBlockState(pos.down()).isAir() &&
                !LEAF_BLOCKS.contains(world.getBlockState(pos.down()).getBlock());
    }


    private static void harvestTreeByRoot(MinecraftClient client, TreeData tree) {
        if (client.player == null || client.world == null) return;

        sendActionBarMessage("Coupe de la racine...", Formatting.YELLOW);

        // Casser physiquement la racine
        breakBlock(client, tree.root);

        sleep(100); // Petit délai

        sendActionBarMessage("L'arbre s'effondre...", Formatting.GOLD);

        dropAllWoodBlocks(client, tree);

        dropSomeLeaves(client, tree);

        playTreeFallSound(client);
        sendActionBarMessage("✓ Arbre abattu! (" + tree.allWoodBlocks.size() + " blocs)", Formatting.GREEN);
    }


    private static void dropAllWoodBlocks(MinecraftClient client, TreeData tree) {
        World world = client.world;
        if (world == null) return;

        for (BlockPos pos : tree.allWoodBlocks) {
            if (pos.equals(tree.root)) continue; // La racine est déjà cassée

            BlockState state = world.getBlockState(pos);
            if (WOOD_BLOCKS.contains(state.getBlock())) {
                // Créer l'item drop pour ce bloc
                ItemStack itemStack = new ItemStack(state.getBlock().asItem());

                // Faire apparaître l'item dans le monde
                ItemEntity itemEntity = new ItemEntity(world,
                        pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, itemStack);

                // Ajouter un peu de vélocité aléatoire pour l'effet
                itemEntity.setVelocity(
                        (Math.random() - 0.5) * 0.3,
                        Math.random() * 0.2 + 0.1,
                        (Math.random() - 0.5) * 0.3
                );

                world.spawnEntity(itemEntity);

                world.setBlockState(pos, Blocks.AIR.getDefaultState());
            }
        }
    }


    private static void dropSomeLeaves(MinecraftClient client, TreeData tree) {
        World world = client.world;
        if (world == null) return;


        List<BlockPos> leaves = new ArrayList<>(tree.leafBlocks);
        Collections.shuffle(leaves);
        int maxLeaves = Math.min(leaves.size(), 20);

        for (int i = 0; i < maxLeaves; i++) {
            BlockPos pos = leaves.get(i);
            BlockState state = world.getBlockState(pos);

            if (LEAF_BLOCKS.contains(state.getBlock())) {
                if (Math.random() < 0.3) {
                    ItemStack itemStack = new ItemStack(state.getBlock().asItem());
                    ItemEntity itemEntity = new ItemEntity(world,
                            pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, itemStack);

                    itemEntity.setVelocity(
                            (Math.random() - 0.5) * 0.2,
                            Math.random() * 0.1,
                            (Math.random() - 0.5) * 0.2
                    );

                    world.spawnEntity(itemEntity);
                }

                // Remplacer par de l'air
                world.setBlockState(pos, Blocks.AIR.getDefaultState());
            }
        }
    }


    private static void sendActionBarMessage(String message, Formatting color) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.player != null) {
            client.player.sendMessage(Text.literal(message).formatted(color), true);
        }
    }

    static void teleportPlayer(MinecraftClient client, Vec3d pos, float yaw, float pitch) {
        if (client.player != null && client.player.networkHandler != null) {
            // Créer et envoyer le packet de téléportation
            PlayerMoveC2SPacket.Full packet = new PlayerMoveC2SPacket.Full(
                    pos.x, pos.y, pos.z, yaw, pitch, true, false
            );
            client.player.networkHandler.sendPacket(packet);

            // Mettre à jour la position locale du joueur
            client.player.setPosition(pos.x, pos.y, pos.z);
            client.player.setYaw(yaw);
            client.player.setPitch(pitch);
        }
    }


    private static void breakBlock(MinecraftClient client, BlockPos pos) {
        if (client.player != null && client.player.networkHandler != null) {
            client.player.networkHandler.sendPacket(
                    new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, pos, Direction.UP)
            );
            client.player.networkHandler.sendPacket(
                    new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, pos, Direction.UP)
            );
        }
    }

    /**
     * Joue un son d'arbre qui tombe
     */
    private static void playTreeFallSound(MinecraftClient client) {
        if (client.world != null && client.player != null) {
            client.world.playSound(client.player, client.player.getBlockPos(),
                    SoundEvents.BLOCK_WOOD_BREAK, SoundCategory.BLOCKS, 1.0f, 1.0f);
        }
    }


    private static void sleep(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }


    private static class TreeData {
        final BlockPos root;
        final Set<BlockPos> allWoodBlocks;
        final Set<BlockPos> leafBlocks;
        final int height;

        TreeData(BlockPos root, Set<BlockPos> allWoodBlocks, Set<BlockPos> leafBlocks, int height) {
            this.root = root;
            this.allWoodBlocks = allWoodBlocks;
            this.leafBlocks = leafBlocks;
            this.height = height;
        }
    }
}