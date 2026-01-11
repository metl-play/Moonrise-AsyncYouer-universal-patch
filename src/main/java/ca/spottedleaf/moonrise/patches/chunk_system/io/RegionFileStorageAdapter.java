package ca.spottedleaf.moonrise.patches.chunk_system.io;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public final class RegionFileStorageAdapter implements ChunkSystemRegionFileStorage {

    private static final Method WRITE_METHOD = findWriteMethod();

    private final RegionFileStorage storage;

    public static ChunkSystemRegionFileStorage wrap(final RegionFileStorage storage) {
        if (storage instanceof ChunkSystemRegionFileStorage chunkSystemStorage) {
            return chunkSystemStorage;
        }
        return new RegionFileStorageAdapter(storage);
    }

    private RegionFileStorageAdapter(final RegionFileStorage storage) {
        this.storage = storage;
    }

    @Override
    public boolean moonrise$doesRegionFileNotExistNoIO(final int chunkX, final int chunkZ) {
        return false;
    }

    @Override
    public RegionFile moonrise$getRegionFileIfLoaded(final int chunkX, final int chunkZ) {
        return null;
    }

    @Override
    public RegionFile moonrise$getRegionFileIfExists(final int chunkX, final int chunkZ) throws IOException {
        return null;
    }

    @Override
    public MoonriseRegionFileIO.RegionDataController.WriteData moonrise$startWrite(
            final int chunkX, final int chunkZ, final CompoundTag compound
    ) throws IOException {
        if (compound == null) {
            return new MoonriseRegionFileIO.RegionDataController.WriteData(
                    compound, MoonriseRegionFileIO.RegionDataController.WriteData.WriteResult.DELETE,
                    null, null
            );
        }

        final ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        return new MoonriseRegionFileIO.RegionDataController.WriteData(
                compound, MoonriseRegionFileIO.RegionDataController.WriteData.WriteResult.WRITE,
                null, (final RegionFile unused) -> this.write(pos, compound)
        );
    }

    @Override
    public void moonrise$finishWrite(
            final int chunkX, final int chunkZ, final MoonriseRegionFileIO.RegionDataController.WriteData writeData
    ) throws IOException {
        final ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        if (writeData.result() == MoonriseRegionFileIO.RegionDataController.WriteData.WriteResult.DELETE) {
            this.write(pos, null);
            return;
        }

        if (writeData.write() != null) {
            writeData.write().run(null);
            return;
        }

        this.write(pos, writeData.input());
    }

    @Override
    public MoonriseRegionFileIO.RegionDataController.ReadData moonrise$readData(
            final int chunkX, final int chunkZ
    ) throws IOException {
        final CompoundTag tag;
        synchronized (this.storage) {
            tag = this.storage.read(new ChunkPos(chunkX, chunkZ));
        }

        if (tag == null) {
            return new MoonriseRegionFileIO.RegionDataController.ReadData(
                    MoonriseRegionFileIO.RegionDataController.ReadData.ReadResult.NO_DATA, null, null
            );
        }

        return new MoonriseRegionFileIO.RegionDataController.ReadData(
                MoonriseRegionFileIO.RegionDataController.ReadData.ReadResult.SYNC_READ, null, tag
        );
    }

    @Override
    public CompoundTag moonrise$finishRead(
            final int chunkX, final int chunkZ, final MoonriseRegionFileIO.RegionDataController.ReadData readData
    ) throws IOException {
        if (readData.result() == MoonriseRegionFileIO.RegionDataController.ReadData.ReadResult.SYNC_READ) {
            return readData.syncRead();
        }

        try {
            return NbtIo.read(readData.input());
        } finally {
            if (readData.input() != null) {
                readData.input().close();
            }
        }
    }

    private void write(final ChunkPos pos, final CompoundTag compound) throws IOException {
        if (WRITE_METHOD == null) {
            throw new IOException("RegionFileStorage.write is not available");
        }

        try {
            synchronized (this.storage) {
                WRITE_METHOD.invoke(this.storage, pos, compound);
            }
        } catch (final IllegalAccessException error) {
            throw new IOException(error);
        } catch (final InvocationTargetException error) {
            final Throwable cause = error.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error fatal) {
                throw fatal;
            }
            throw new IOException(cause);
        }
    }

    private static Method findWriteMethod() {
        try {
            final Method method = RegionFileStorage.class.getDeclaredMethod(
                    "write", ChunkPos.class, CompoundTag.class
            );
            method.setAccessible(true);
            return method;
        } catch (final ReflectiveOperationException ignored) {
            return null;
        }
    }
}
