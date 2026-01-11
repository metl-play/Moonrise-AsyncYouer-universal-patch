package ca.spottedleaf.moonrise.patches.chunk_system.io;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import java.io.IOException;

// Compatibility shim for legacy RegionFileIOThread API users.
public final class RegionFileIOThread {

    private RegionFileIOThread() {
    }

    public enum RegionFileType {
        CHUNK_DATA,
        POI_DATA,
        ENTITY_DATA;
    }

    public interface ChunkDataController {
    }

    public static ChunkDataController getControllerFor(final ServerLevel world, final RegionFileType type) {
        return (ChunkDataController)MoonriseRegionFileIO.getControllerFor(world,
                MoonriseRegionFileIO.RegionFileType.valueOf(type.name()));
    }

    public static void flushRegionStorages(final ServerLevel world) throws IOException {
        MoonriseRegionFileIO.flushRegionStorages(world);
    }

    public static void flushRegionStorages(final ServerLevel world, final RegionFileType type) throws IOException {
        MoonriseRegionFileIO.flushRegionStorages(world, MoonriseRegionFileIO.RegionFileType.valueOf(type.name()));
    }

    public static void flush(final MinecraftServer server) {
        MoonriseRegionFileIO.flush(server);
    }

    public static void flush(final ServerLevel world) {
        MoonriseRegionFileIO.flush(world);
    }

    public static void partialFlush(final ServerLevel world, final int tasksRemaining) {
        MoonriseRegionFileIO.partialFlush(world, tasksRemaining);
    }
}
