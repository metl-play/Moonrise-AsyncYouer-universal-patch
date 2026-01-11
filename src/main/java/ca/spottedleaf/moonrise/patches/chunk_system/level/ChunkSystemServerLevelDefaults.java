package ca.spottedleaf.moonrise.patches.chunk_system.level;

import ca.spottedleaf.moonrise.patches.chunk_system.io.datacontroller.ChunkDataController;
import ca.spottedleaf.moonrise.patches.chunk_system.io.datacontroller.EntityDataController;
import ca.spottedleaf.moonrise.patches.chunk_system.io.datacontroller.PoiDataController;
import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.storage.LevelStorageSource;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

final class ChunkSystemServerLevelDefaults {

    private static final Map<ServerLevel, Controllers> CONTROLLERS =
            Collections.synchronizedMap(new WeakHashMap<>());

    static MoonriseRegionFileIO.RegionDataController getChunkDataController(final ServerLevel level) {
        return getControllers(level).chunkDataController;
    }

    static MoonriseRegionFileIO.RegionDataController getPoiChunkDataController(final ServerLevel level) {
        return getControllers(level).poiDataController;
    }

    static MoonriseRegionFileIO.RegionDataController getEntityChunkDataController(final ServerLevel level) {
        return getControllers(level).entityDataController;
    }

    private static Controllers getControllers(final ServerLevel level) {
        Controllers controllers = CONTROLLERS.get(level);
        if (controllers != null) {
            return controllers;
        }
        controllers = createControllers(level);
        CONTROLLERS.put(level, controllers);
        return controllers;
    }

    private static Controllers createControllers(final ServerLevel level) {
        final ChunkTaskScheduler scheduler = getScheduler(level);
        final ChunkDataController chunkDataController = new ChunkDataController(level, scheduler);
        final PoiDataController poiDataController = new PoiDataController(level, scheduler);

        final MinecraftServer server = level.getServer();
        final LevelStorageSource.LevelStorageAccess storageAccess = getStorageAccess(server, level);

        final EntityDataController.EntityRegionFileStorage storage =
                new EntityDataController.EntityRegionFileStorage(
                        new RegionStorageInfo(storageAccess.getLevelId(), level.dimension(), "entities"),
                        storageAccess.getDimensionPath(level.dimension()).resolve("entities"),
                        server.forceSynchronousWrites()
                );

        final EntityDataController entityDataController = new EntityDataController(storage, scheduler);
        return new Controllers(chunkDataController, poiDataController, entityDataController);
    }

    private static ChunkTaskScheduler getScheduler(final ServerLevel level) {
        try {
            final ChunkTaskScheduler scheduler = ((ChunkSystemServerLevel)(Object)level).moonrise$getChunkTaskScheduler();
            return scheduler != null ? scheduler : new ChunkTaskScheduler(level);
        } catch (final AbstractMethodError ignored) {
            return new ChunkTaskScheduler(level);
        }
    }

    private static LevelStorageSource.LevelStorageAccess getStorageAccess(final MinecraftServer server, final ServerLevel level) {
        try {
            final java.lang.reflect.Field field = MinecraftServer.class.getDeclaredField("storageSource");
            field.setAccessible(true);
            final Object value = field.get(server);
            if (value instanceof LevelStorageSource.LevelStorageAccess access) {
                return access;
            }
        } catch (final ReflectiveOperationException ignored) {
        }
        throw new IllegalStateException("LevelStorageAccess is not available for " + level.dimension().location());
    }

    private record Controllers(
            ChunkDataController chunkDataController,
            PoiDataController poiDataController,
            EntityDataController entityDataController
    ) {
    }

    private ChunkSystemServerLevelDefaults() {}
}
