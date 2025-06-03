package org.rapido.betterfarming.client.mixins;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.rapido.betterfarming.client.TreeCutter.AutoTreeCutter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Block.class)
public class BlockMixin {

    @Inject(method = "onBreak", at = @At("HEAD"))
    private void onBlockBreak(World world, BlockPos pos, BlockState state, PlayerEntity player, CallbackInfoReturnable<BlockState> cir) {
        // Appeler notre système de physique d'arbre
        AutoTreeCutter.onWoodBlockBroken(world, pos, state);
    }
}