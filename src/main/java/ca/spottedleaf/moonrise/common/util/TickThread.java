package ca.spottedleaf.moonrise.common.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

public class TickThread extends Thread {

    private static final Logger LOGGER = LoggerFactory.getLogger(TickThread.class);
    private static final ThreadLocal<ChunkTaskContext> CHUNK_TASK_CONTEXT = new ThreadLocal<>();

    /**
     * @deprecated
     */
    @Deprecated
    public static void ensureTickThread(final String reason) {
        if (!isTickThread()) {
            LOGGER.error("Thread " + Thread.currentThread().getName() + " failed main thread check: " + reason, new Throwable());
            throw new IllegalStateException(reason);
        }
    }

    public static void ensureTickThread(final Level world, final BlockPos pos, final String reason) {
        if (!isTickThreadFor(world, pos)) {
            LOGGER.error("Thread " + Thread.currentThread().getName() + " failed main thread check: " + reason, new Throwable());
            throw new IllegalStateException(reason);
        }
    }

    public static void ensureTickThread(final Level world, final ChunkPos pos, final String reason) {
        if (!isTickThreadFor(world, pos)) {
            LOGGER.error("Thread " + Thread.currentThread().getName() + " failed main thread check: " + reason, new Throwable());
            throw new IllegalStateException(reason);
        }
    }

    public static void ensureTickThread(final Level world, final int chunkX, final int chunkZ, final String reason) {
        if (!isTickThreadFor(world, chunkX, chunkZ)) {
            LOGGER.error("Thread " + Thread.currentThread().getName() + " failed main thread check: " + reason, new Throwable());
            throw new IllegalStateException(reason);
        }
    }

    public static void ensureTickThread(final Entity entity, final String reason) {
        if (!isTickThreadFor(entity)) {
            LOGGER.error("Thread " + Thread.currentThread().getName() + " failed main thread check: " + reason, new Throwable());
            throw new IllegalStateException(reason);
        }
    }

    public static void ensureTickThread(final Level world, final AABB aabb, final String reason) {
        if (!isTickThreadFor(world, aabb)) {
            LOGGER.error("Thread " + Thread.currentThread().getName() + " failed main thread check: " + reason, new Throwable());
            throw new IllegalStateException(reason);
        }
    }

    public static void ensureTickThread(final Level world, final double blockX, final double blockZ, final String reason) {
        if (!isTickThreadFor(world, blockX, blockZ)) {
            LOGGER.error("Thread " + Thread.currentThread().getName() + " failed main thread check: " + reason, new Throwable());
            throw new IllegalStateException(reason);
        }
    }

    public final int id; /* We don't override getId as the spec requires that it be unique (with respect to all other threads) */

    private static final AtomicInteger ID_GENERATOR = new AtomicInteger();

    public TickThread(final String name) {
        this(null, name);
    }

    public TickThread(final Runnable run, final String name) {
        this(null, run, name);
    }

    public TickThread(final ThreadGroup group, final Runnable run, final String name) {
        this(group, run, name, ID_GENERATOR.incrementAndGet());
    }

    private TickThread(final ThreadGroup group, final Runnable run, final String name, final int id) {
        super(group, run, name);
        this.id = id;
    }

    public static TickThread getCurrentTickThread() {
        return (TickThread)Thread.currentThread();
    }

    public static ChunkTaskContext pushChunkTaskContext(final Level world, final int chunkX, final int chunkZ, final int radius) {
        final ChunkTaskContext previous = CHUNK_TASK_CONTEXT.get();
        CHUNK_TASK_CONTEXT.set(new ChunkTaskContext(world, chunkX, chunkZ, radius, previous));
        return previous;
    }

    public static void popChunkTaskContext(final ChunkTaskContext previous) {
        CHUNK_TASK_CONTEXT.set(previous);
    }

    public static boolean isTickThread() {
        return Thread.currentThread() instanceof TickThread;
    }

    public static boolean isShutdownThread() {
        return false;
    }

    public static boolean isTickThreadFor(final Level world, final BlockPos pos) {
        return isTickThread() || isChunkTaskThreadFor(world, pos.getX() >> 4, pos.getZ() >> 4);
    }

    public static boolean isTickThreadFor(final Level world, final ChunkPos pos) {
        return isTickThread() || isChunkTaskThreadFor(world, pos.x, pos.z);
    }

    public static boolean isTickThreadFor(final Level world, final Vec3 pos) {
        final int chunkX = ((int)Math.floor(pos.x)) >> 4;
        final int chunkZ = ((int)Math.floor(pos.z)) >> 4;
        return isTickThread() || isChunkTaskThreadFor(world, chunkX, chunkZ);
    }

    public static boolean isTickThreadFor(final Level world, final int chunkX, final int chunkZ) {
        return isTickThread() || isChunkTaskThreadFor(world, chunkX, chunkZ);
    }

    public static boolean isTickThreadFor(final Level world, final AABB aabb) {
        if (isTickThread()) {
            return true;
        }
        final int minChunkX = ((int)Math.floor(aabb.minX)) >> 4;
        final int minChunkZ = ((int)Math.floor(aabb.minZ)) >> 4;
        final int maxChunkX = ((int)Math.floor(aabb.maxX)) >> 4;
        final int maxChunkZ = ((int)Math.floor(aabb.maxZ)) >> 4;
        return isChunkTaskThreadForRange(world, minChunkX, minChunkZ, maxChunkX, maxChunkZ);
    }

    public static boolean isTickThreadFor(final Level world, final double blockX, final double blockZ) {
        final int chunkX = ((int)Math.floor(blockX)) >> 4;
        final int chunkZ = ((int)Math.floor(blockZ)) >> 4;
        return isTickThread() || isChunkTaskThreadFor(world, chunkX, chunkZ);
    }

    public static boolean isTickThreadFor(final Level world, final Vec3 position, final Vec3 deltaMovement, final int buffer) {
        if (isTickThread()) {
            return true;
        }
        final double endX = position.x + deltaMovement.x;
        final double endZ = position.z + deltaMovement.z;
        final double minX = Math.min(position.x, endX) - buffer;
        final double minZ = Math.min(position.z, endZ) - buffer;
        final double maxX = Math.max(position.x, endX) + buffer;
        final double maxZ = Math.max(position.z, endZ) + buffer;
        final int minChunkX = ((int)Math.floor(minX)) >> 4;
        final int minChunkZ = ((int)Math.floor(minZ)) >> 4;
        final int maxChunkX = ((int)Math.floor(maxX)) >> 4;
        final int maxChunkZ = ((int)Math.floor(maxZ)) >> 4;
        return isChunkTaskThreadForRange(world, minChunkX, minChunkZ, maxChunkX, maxChunkZ);
    }

    public static boolean isTickThreadFor(final Level world, final int fromChunkX, final int fromChunkZ, final int toChunkX, final int toChunkZ) {
        return isTickThread() || isChunkTaskThreadForRange(world, fromChunkX, fromChunkZ, toChunkX, toChunkZ);
    }

    public static boolean isTickThreadFor(final Level world, final int chunkX, final int chunkZ, final int radius) {
        if (isTickThread()) {
            return true;
        }
        final int minChunkX = chunkX - radius;
        final int minChunkZ = chunkZ - radius;
        final int maxChunkX = chunkX + radius;
        final int maxChunkZ = chunkZ + radius;
        return isChunkTaskThreadForRange(world, minChunkX, minChunkZ, maxChunkX, maxChunkZ);
    }

    public static boolean isTickThreadFor(final Entity entity) {
        if (isTickThread()) {
            return true;
        }
        if (entity == null) {
            return false;
        }
        final ChunkPos pos = entity.chunkPosition();
        return isChunkTaskThreadFor(entity.level(), pos.x, pos.z);
    }

    private static boolean isChunkTaskThreadFor(final Level world, final int chunkX, final int chunkZ) {
        return isChunkTaskThreadForRange(world, chunkX, chunkZ, chunkX, chunkZ);
    }

    private static boolean isChunkTaskThreadForRange(final Level world, final int fromChunkX, final int fromChunkZ,
                                                     final int toChunkX, final int toChunkZ) {
        int minChunkX = Math.min(fromChunkX, toChunkX);
        int minChunkZ = Math.min(fromChunkZ, toChunkZ);
        int maxChunkX = Math.max(fromChunkX, toChunkX);
        int maxChunkZ = Math.max(fromChunkZ, toChunkZ);
        for (ChunkTaskContext ctx = CHUNK_TASK_CONTEXT.get(); ctx != null; ctx = ctx.previous) {
            if (ctx.world != world) {
                continue;
            }
            if (ctx.radius < 0) {
                return true;
            }
            final int ctxMinX = ctx.chunkX - ctx.radius;
            final int ctxMinZ = ctx.chunkZ - ctx.radius;
            final int ctxMaxX = ctx.chunkX + ctx.radius;
            final int ctxMaxZ = ctx.chunkZ + ctx.radius;
            if (minChunkX >= ctxMinX && maxChunkX <= ctxMaxX && minChunkZ >= ctxMinZ && maxChunkZ <= ctxMaxZ) {
                return true;
            }
        }
        return false;
    }

    public static final class ChunkTaskContext {
        private final Level world;
        private final int chunkX;
        private final int chunkZ;
        private final int radius;
        private final ChunkTaskContext previous;

        private ChunkTaskContext(final Level world, final int chunkX, final int chunkZ, final int radius,
                                 final ChunkTaskContext previous) {
            this.world = world;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.radius = radius;
            this.previous = previous;
        }
    }
}
