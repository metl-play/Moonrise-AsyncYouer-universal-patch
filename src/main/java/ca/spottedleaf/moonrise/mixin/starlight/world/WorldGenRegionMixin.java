package ca.spottedleaf.moonrise.mixin.starlight.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkDependencies;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WorldGenRegion.class)
abstract class WorldGenRegionMixin implements WorldGenLevel {

    @Shadow
    @Final
    private StaticCache2D<GenerationChunkHolder> cache;

    @Shadow
    @Final
    private ChunkAccess center;

    @Shadow
    @Final
    private ChunkStep generatingStep;

    @Shadow
    public abstract ChunkAccess getChunk(int i, int j);

    // Allow biome queries outside the strict feature dependencies to fall back to noise for compatibility.
    @Inject(
            method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void moonrise$allowBiomeQuery(final int chunkX, final int chunkZ, final ChunkStatus status, final boolean create,
                                          final CallbackInfoReturnable<ChunkAccess> cir) {
        if (create || status != ChunkStatus.BIOMES) {
            return;
        }

        final ChunkPos centerPos = this.center.getPos();
        final int distance = centerPos.getChessboardDistance(chunkX, chunkZ);
        final ChunkDependencies dependencies = this.generatingStep.directDependencies();

        if (distance >= dependencies.size()) {
            cir.setReturnValue(null);
            return;
        }

        final ChunkStatus maxStatus = dependencies.get(distance);
        if (maxStatus == null || status.isOrBefore(maxStatus)) {
            return;
        }

        if (!this.cache.contains(chunkX, chunkZ)) {
            cir.setReturnValue(null);
            return;
        }

        final GenerationChunkHolder holder = this.cache.get(chunkX, chunkZ);
        final ChunkAccess chunk = holder == null ? null : holder.getChunkIfPresentUnchecked(status);
        cir.setReturnValue(chunk);
    }

    /**
     * During feature generation, light data is not initialised and will always return 15 in Starlight. Vanilla
     * can possibly return 0 if partially initialised, which allows some mushroom blocks to generate.
     * In general, the brightness value from the light engine should not be used until the chunk is ready. To emulate
     * Vanilla behavior better, we return 0 as the brightness during world gen unless the target chunk is finished
     * lighting.
     * @author Spottedleaf
     */
    @Override
    public int getBrightness(final LightLayer lightLayer, final BlockPos blockPos) {
        final ChunkAccess chunk = this.getChunk(blockPos.getX() >> 4, blockPos.getZ() >> 4);
        if (!chunk.isLightCorrect()) {
            return 0;
        }
        return this.getLightEngine().getLayerListener(lightLayer).getLightValue(blockPos);
    }

    /**
     * See above
     * @author Spottedleaf
     */
    @Override
    public int getRawBrightness(final BlockPos blockPos, final int subtract) {
        final ChunkAccess chunk = this.getChunk(blockPos.getX() >> 4, blockPos.getZ() >> 4);
        if (!chunk.isLightCorrect()) {
            return 0;
        }
        return this.getLightEngine().getRawBrightness(blockPos, subtract);
    }
}
