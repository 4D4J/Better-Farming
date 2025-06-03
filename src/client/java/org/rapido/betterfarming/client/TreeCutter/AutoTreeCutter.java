package org.rapido.betterfarming.client.TreeCutter;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundEvents;
import net.minecraft.sound.SoundCategory;
import net.minecraft.entity.player.PlayerEntity;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class AutoTreeCutter {

    // Configuration
    private static final int MAX_TREE_HEIGHT = 30;
    private static final int MAX_TREE_RADIUS = 15;
    private static final int MIN_TREE_SIZE = 3;
    private static final int ROOT_SEARCH_RADIUS = 10;

    // Types de blocs - Mis à jour pour 1.21.4
    private static final Set<Block> WOOD_BLOCKS = Set.of(
            Blocks.OAK_LOG, Blocks.BIRCH_LOG, Blocks.SPRUCE_LOG,
            Blocks.JUNGLE_LOG, Blocks.ACACIA_LOG, Blocks.DARK_OAK_LOG,
            Blocks.MANGROVE_LOG, Blocks.CHERRY_LOG, Blocks.CRIMSON_STEM,
            Blocks.WARPED_STEM, Blocks.STRIPPED_OAK_LOG, Blocks.STRIPPED_BIRCH_LOG,
            Blocks.STRIPPED_SPRUCE_LOG, Blocks.STRIPPED_JUNGLE_LOG,
            Blocks.STRIPPED_ACACIA_LOG, Blocks.STRIPPED_DARK_OAK_LOG,
            Blocks.STRIPPED_MANGROVE_LOG, Blocks.STRIPPED_CHERRY_LOG,
            Blocks.STRIPPED_CRIMSON_STEM, Blocks.STRIPPED_WARPED_STEM,
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
            Blocks.WARPED_NYLIUM, Blocks.MUD, Blocks.MUDDY_MANGROVE_ROOTS,
            Blocks.STONE, Blocks.DEEPSLATE
    );

    // Données d'un arbre
    private static class TreeData {
        final Set<BlockPos> woodBlocks;
        final Set<BlockPos> leafBlocks;
        final BlockPos root;

        TreeData(Set<BlockPos> woodBlocks, Set<BlockPos> leafBlocks, BlockPos root) {
            this.woodBlocks = woodBlocks;
            this.leafBlocks = leafBlocks;
            this.root = root;
        }
    }


    public static void onWoodBlockBroken(World world, BlockPos pos, BlockState state) {
        if (!(world instanceof ServerWorld serverWorld)) return;
        if (!WOOD_BLOCKS.contains(state.getBlock())) return;

        // Vérifier si c'était une racine d'arbre
        if (isTreeRoot(world, pos)) {
            // Analyser l'arbre complet
            TreeData tree = analyzeTreeFromRoot(world, pos);

            if (tree != null && tree.woodBlocks.size() >= MIN_TREE_SIZE) {
                // Faire s'effondrer l'arbre de manière asynchrone pour éviter les lags
                CompletableFuture.runAsync(() -> collapseTree(serverWorld, tree));
            }
        } else {
            // Si ce n'est pas une racine, vérifier si l'arbre est toujours stable
            checkTreeStability(serverWorld, pos);
        }
    }

    /**
     * Recherche et coupe toutes les racines d'arbres dans un rayon autour du joueur
     * @param world Le monde
     * @param player Le joueur
     * @return Nombre de racines coupées
     */
    public static int cutNearbyTreeRoots(World world, PlayerEntity player) {
        // Fonctionne côté client, aucune vérification de ServerWorld
        BlockPos playerPos = player.getBlockPos();
        int rootsCut = 0;
        List<BlockPos> rootPositions = new ArrayList<>();

        // Rechercher toutes les racines d'arbres dans le rayon
        for (int x = -ROOT_SEARCH_RADIUS; x <= ROOT_SEARCH_RADIUS; x++) {
            for (int y = -3; y <= 3; y++) {  // Chercher légèrement au-dessus/en-dessous du joueur
                for (int z = -ROOT_SEARCH_RADIUS; z <= ROOT_SEARCH_RADIUS; z++) {
                    BlockPos pos = playerPos.add(x, y, z);

                    // Vérifier si c'est un bloc de bois
                    BlockState state = world.getBlockState(pos);
                    if (WOOD_BLOCKS.contains(state.getBlock()) && isTreeRootEnhanced(world, pos)) {
                        rootPositions.add(pos);
                    }
                }
            }
        }

        // Debug: afficher le nombre de racines trouvées
        System.out.println("Racines trouvées: " + rootPositions.size());

        // Couper chaque racine une par une
        for (BlockPos rootPos : rootPositions) {
            // Simuler la destruction du bloc (drop d'items et son)
            world.breakBlock(rootPos, true, player);
            world.playSound(null, rootPos, SoundEvents.BLOCK_WOOD_BREAK, SoundCategory.BLOCKS, 1.0f, 1.0f);
            rootsCut++;
        }

        return rootsCut;
    }

    /**
     * Vérifie si le bloc donné est une racine d'arbre (méthode améliorée)
     */
    private static boolean isTreeRootEnhanced(World world, BlockPos pos) {
        // Vérification 1: Y a-t-il du sol en dessous?
        BlockState below = world.getBlockState(pos.down());
        if (GROUND_BLOCKS.contains(below.getBlock())) {
            return true;
        }

        // Vérification 2: Y a-t-il du sol sur les côtés et le bloc est-il près du sol?
        // (pour les racines qui ne sont pas directement au-dessus du sol)
        for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos adjacentPos = pos.offset(dir);
            BlockState adjacentState = world.getBlockState(adjacentPos);

            // Si un bloc adjacent est du sol, c'est potentiellement une racine
            if (GROUND_BLOCKS.contains(adjacentState.getBlock())) {
                // Vérifier si on est proche du sol (dans les 2 blocs au-dessus)
                BlockPos groundCheck = pos.down(2);
                BlockState groundState = world.getBlockState(groundCheck);
                if (GROUND_BLOCKS.contains(groundState.getBlock())) {
                    return true;
                }
            }
        }

        // Vérification 3: Est-ce le bloc de bois le plus bas d'un arbre?
        // (descendre depuis le bloc et voir si on atteint un bloc qui n'est pas du bois)
        BlockPos checkPos = pos.down();
        BlockState checkState = world.getBlockState(checkPos);

        // Si le bloc en dessous n'est pas du bois, c'est potentiellement la racine
        if (!WOOD_BLOCKS.contains(checkState.getBlock())) {
            // Vérifions aussi qu'il y a d'autres blocs de bois au-dessus (c'est un arbre)
            BlockPos above = pos.up();
            BlockState aboveState = world.getBlockState(above);
            if (WOOD_BLOCKS.contains(aboveState.getBlock())) {
                return true;
            }
        }

        return false;
    }

    private static boolean isTreeRoot(World world, BlockPos pos) {
        // Un bloc est considéré comme racine s'il y a du sol en dessous
        BlockState below = world.getBlockState(pos.down());
        return GROUND_BLOCKS.contains(below.getBlock());
    }

    /**
     * Analyse complètement un arbre à partir de sa racine
     */
    private static TreeData analyzeTreeFromRoot(World world, BlockPos root) {
        Set<BlockPos> woodBlocks = new HashSet<>();
        Set<BlockPos> leafBlocks = new HashSet<>();
        Set<BlockPos> visited = new HashSet<>();
        Queue<BlockPos> toExplore = new LinkedList<>();

        toExplore.add(root);

        while (!toExplore.isEmpty()) {
            BlockPos pos = toExplore.poll();

            if (visited.contains(pos) ||
                    pos.getY() - root.getY() > MAX_TREE_HEIGHT ||
                    pos.getSquaredDistance(root) > MAX_TREE_RADIUS * MAX_TREE_RADIUS) {
                continue;
            }

            visited.add(pos);
            BlockState state = world.getBlockState(pos);
            Block block = state.getBlock();

            if (WOOD_BLOCKS.contains(block)) {
                woodBlocks.add(pos);

                // Explorer toutes les directions + diagonales pour les blocs de bois
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if (dx == 0 && dy == 0 && dz == 0) continue;
                            BlockPos next = pos.add(dx, dy, dz);
                            if (!visited.contains(next)) {
                                toExplore.add(next);
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

        return woodBlocks.isEmpty() ? null : new TreeData(woodBlocks, leafBlocks, root);
    }

    /**
     * Fait s'effondrer l'arbre avec effet visuel
     */
    private static void collapseTree(ServerWorld world, TreeData tree) {
        // Trier les blocs par hauteur (du haut vers le bas)
        List<BlockPos> sortedWoodBlocks = new ArrayList<>(tree.woodBlocks);
        sortedWoodBlocks.sort((a, b) -> Integer.compare(b.getY(), a.getY()));

        // Faire tomber les blocs par vagues pour un effet plus réaliste
        int totalBlocks = sortedWoodBlocks.size();
        int wavesCount = Math.min(5, totalBlocks / 3 + 1);
        int blocksPerWave = totalBlocks / wavesCount;

        for (int wave = 0; wave < wavesCount; wave++) {
            int startIndex = wave * blocksPerWave;
            int endIndex = (wave == wavesCount - 1) ? totalBlocks : (wave + 1) * blocksPerWave;

            List<BlockPos> waveBlocks = sortedWoodBlocks.subList(startIndex, endIndex);

            // Délai entre les vagues pour l'effet visuel
            int delay = wave * 100; // 100ms entre chaque vague

            world.getServer().execute(() -> {
                try {
                    Thread.sleep(delay);
                    dropBlocksWave(world, waveBlocks);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
    }

    private static void dropBlocksWave(ServerWorld world, List<BlockPos> blocks) {
        for (BlockPos pos : blocks) {
            BlockState state = world.getBlockState(pos);
            if (WOOD_BLOCKS.contains(state.getBlock())) {
                // Faire dropper le bloc
                Block.dropStacks(state, world, pos);

                // Remplacer par de l'air
                world.setBlockState(pos, Blocks.AIR.getDefaultState());
            }
        }
    }

    private static void checkTreeStability(ServerWorld world, BlockPos brokenPos) {
        // Chercher des blocs de bois connectés qui pourraient être instables
        Set<BlockPos> connectedWood = findConnectedWoodBlocks(world, brokenPos);

        for (BlockPos woodPos : connectedWood) {
            if (!isWoodBlockStable(world, woodPos)) {
                // Ce bloc n'est plus stable, le faire tomber
                BlockState state = world.getBlockState(woodPos);
                if (WOOD_BLOCKS.contains(state.getBlock())) {
                    // Petit délai pour un effet en cascade
                    world.getServer().execute(() -> {
                        try {
                            Thread.sleep(world.random.nextInt(200) + 100);

                            Block.dropStacks(state, world, woodPos);
                            world.setBlockState(woodPos, Blocks.AIR.getDefaultState());
                            world.syncWorldEvent(2001, woodPos, Block.getRawIdFromState(state));

                            // Vérifier récursivement les blocs connectés
                            checkTreeStability(world, woodPos);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                }
            }
        }
    }

    /**
     * Trouve tous les blocs de bois connectés à une position
     */
    private static Set<BlockPos> findConnectedWoodBlocks(World world, BlockPos center) {
        Set<BlockPos> connected = new HashSet<>();
        Set<BlockPos> visited = new HashSet<>();
        Queue<BlockPos> toCheck = new LinkedList<>();

        // Vérifier dans un rayon de 3 blocs autour de la position cassée
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos pos = center.add(dx, dy, dz);
                    if (WOOD_BLOCKS.contains(world.getBlockState(pos).getBlock())) {
                        toCheck.add(pos);
                    }
                }
            }
        }

        return connected;
    }

    /**
     * Vérifie si un bloc de bois est stable (connecté au sol)
     */
    private static boolean isWoodBlockStable(World world, BlockPos pos) {
        Set<BlockPos> visited = new HashSet<>();
        Queue<BlockPos> toCheck = new LinkedList<>();
        toCheck.add(pos);

        while (!toCheck.isEmpty() && visited.size() < 50) { // Limite pour éviter les boucles infinies
            BlockPos current = toCheck.poll();
            if (visited.contains(current)) continue;
            visited.add(current);

            // Si on trouve le sol, le bloc est stable
            if (GROUND_BLOCKS.contains(world.getBlockState(current.down()).getBlock())) {
                return true;
            }

            // Continuer à chercher dans les blocs de bois connectés
            for (Direction dir : Direction.values()) {
                BlockPos next = current.offset(dir);
                if (!visited.contains(next) &&
                        WOOD_BLOCKS.contains(world.getBlockState(next).getBlock())) {
                    toCheck.add(next);
                }
            }
        }

        return false; // Aucune connexion au sol trouvée
    }
}
