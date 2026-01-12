package ca.spottedleaf.moonrise.patches.chunk_system.io;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.FileUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.slf4j.Logger;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RegionFileStorageAdapter implements ChunkSystemRegionFileStorage {

    private static final Method WRITE_METHOD = findWriteMethod();
    private static final Field REGION_CACHE_FIELD = findField("regionCache", Long2ObjectLinkedOpenHashMap.class, false);
    private static final Field FOLDER_FIELD = findField("folder", Path.class, false);
    private static final Field SYNC_FIELD = findField("sync", boolean.class, false);
    private static final Field INFO_FIELD = findField("info", RegionStorageInfo.class, false);
    private static final Field MAX_CACHE_FIELD = findField("MAX_CACHE_SIZE", int.class, true);
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<RegionFileStorage, StorageAccess> ACCESS_MAP = new WeakHashMap<>();
    private static final AtomicBoolean ACCESS_LOGGED = new AtomicBoolean();
    private static final int REGION_SHIFT = 5;
    private static final int MAX_NON_EXISTING_CACHE = 1024 * 4;

    private final RegionFileStorage storage;
    private final StorageAccess access;

    public static ChunkSystemRegionFileStorage wrap(final RegionFileStorage storage) {
        if (storage instanceof ChunkSystemRegionFileStorage chunkSystemStorage) {
            return chunkSystemStorage;
        }
        return new RegionFileStorageAdapter(storage);
    }

    private RegionFileStorageAdapter(final RegionFileStorage storage) {
        this.storage = storage;
        this.access = getAccess(storage);
    }

    @Override
    public boolean moonrise$doesRegionFileNotExistNoIO(final int chunkX, final int chunkZ) {
        if (this.access == null) {
            return false;
        }
        final long key = ChunkPos.asLong(chunkX >> REGION_SHIFT, chunkZ >> REGION_SHIFT);
        synchronized (this.storage) {
            return !doesRegionFilePossiblyExist(this.access, key);
        }
    }

    @Override
    public RegionFile moonrise$getRegionFileIfLoaded(final int chunkX, final int chunkZ) {
        if (this.access == null) {
            return null;
        }
        final long key = ChunkPos.asLong(chunkX >> REGION_SHIFT, chunkZ >> REGION_SHIFT);
        synchronized (this.storage) {
            try {
                return this.access.regionCache.getAndMoveToFirst(key);
            } catch (final ArrayIndexOutOfBoundsException error) {
                resetAccessCache(this.access, error);
                return null;
            }
        }
    }

    @Override
    public RegionFile moonrise$getRegionFileIfExists(final int chunkX, final int chunkZ) throws IOException {
        if (this.access == null) {
            return null;
        }
        synchronized (this.storage) {
            try {
                return getRegionFileIfExistsInternal(this.access, chunkX, chunkZ);
            } catch (final ArrayIndexOutOfBoundsException error) {
                resetAccessCache(this.access, error);
                return getRegionFileIfExistsInternal(this.access, chunkX, chunkZ);
            }
        }
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
        final CompoundTag tag = this.read(new ChunkPos(chunkX, chunkZ));

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

    private CompoundTag read(final ChunkPos pos) throws IOException {
        if (this.access == null) {
            return this.readWithRecovery(pos);
        }
        synchronized (this.storage) {
            final RegionFile regionFile = getRegionFileIfExistsInternal(this.access, pos.x, pos.z);
            if (regionFile == null) {
                return null;
            }
            final DataInputStream input = regionFile.getChunkDataInputStream(pos);
            try (input) {
                if (input == null) {
                    return null;
                }
                return NbtIo.read(input);
            }
        }
    }

    private void write(final ChunkPos pos, final CompoundTag compound) throws IOException {
        if (this.access == null) {
            this.writeWithRecovery(pos, compound);
            return;
        }
        synchronized (this.storage) {
            if (compound == null) {
                final RegionFile regionFile = getRegionFileIfExistsInternal(this.access, pos.x, pos.z);
                if (regionFile != null) {
                    regionFile.clear(pos);
                }
                return;
            }
            final RegionFile regionFile = getRegionFileInternal(this.access, pos.x, pos.z);
            try (DataOutputStream output = regionFile.getChunkDataOutputStream(pos)) {
                NbtIo.write(compound, output);
            }
        }
    }

    private void writeWithRecovery(final ChunkPos pos, final CompoundTag compound) throws IOException {
        if (WRITE_METHOD == null) {
            throw new IOException("RegionFileStorage.write is not available");
        }

        try {
            synchronized (this.storage) {
                try {
                    this.invokeWrite(pos, compound);
                } catch (final ArrayIndexOutOfBoundsException error) {
                    this.resetRegionCache(error);
                    this.invokeWrite(pos, compound);
                }
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

    private void invokeWrite(final ChunkPos pos, final CompoundTag compound)
            throws IllegalAccessException, InvocationTargetException {
        WRITE_METHOD.invoke(this.storage, pos, compound);
    }

    private CompoundTag readWithRecovery(final ChunkPos pos) throws IOException {
        synchronized (this.storage) {
            try {
                return this.storage.read(pos);
            } catch (final ArrayIndexOutOfBoundsException error) {
                this.resetRegionCache(error);
                return this.storage.read(pos);
            }
        }
    }

    private void resetRegionCache(final ArrayIndexOutOfBoundsException error) {
        if (REGION_CACHE_FIELD == null) {
            LOGGER.error("RegionFileStorage cache corrupted; reset unavailable", error);
            return;
        }

        LOGGER.error("RegionFileStorage cache corrupted; resetting cache", error);
        try {
            final Object cache = REGION_CACHE_FIELD.get(this.storage);
            if (!(cache instanceof Long2ObjectLinkedOpenHashMap<?> map)) {
                return;
            }
            for (final Object value : map.values()) {
                if (value instanceof RegionFile regionFile) {
                    try {
                        regionFile.close();
                    } catch (final IOException ignored) {
                        // best-effort cleanup
                    }
                }
            }
            map.clear();
        } catch (final IllegalAccessException ignored) {
            // best-effort recovery; next IO will likely fail again
        }
    }

    private static StorageAccess getAccess(final RegionFileStorage storage) {
        synchronized (ACCESS_MAP) {
            StorageAccess access = ACCESS_MAP.get(storage);
            if (access != null) {
                return access;
            }
            access = createAccess(storage);
            if (access != null) {
                ACCESS_MAP.put(storage, access);
            }
            return access;
        }
    }

    private static StorageAccess createAccess(final RegionFileStorage storage) {
        if (FOLDER_FIELD == null || SYNC_FIELD == null || INFO_FIELD == null || MAX_CACHE_FIELD == null || REGION_CACHE_FIELD == null) {
            if (ACCESS_LOGGED.compareAndSet(false, true)) {
                LOGGER.warn("RegionFileStorage adapter missing reflective fields; falling back to direct storage access.");
            }
            return null;
        }
        try {
            final Path folder = (Path)FOLDER_FIELD.get(storage);
            final boolean sync = SYNC_FIELD.getBoolean(storage);
            final RegionStorageInfo info = (RegionStorageInfo)INFO_FIELD.get(storage);
            final int maxCache = MAX_CACHE_FIELD.getInt(null);
            final Object cache = REGION_CACHE_FIELD.get(storage);
            if (!(cache instanceof Long2ObjectLinkedOpenHashMap<?> map)) {
                if (ACCESS_LOGGED.compareAndSet(false, true)) {
                    LOGGER.warn("RegionFileStorage adapter could not resolve region cache; falling back to direct storage access.");
                }
                return null;
            }
            if (folder == null || info == null) {
                if (ACCESS_LOGGED.compareAndSet(false, true)) {
                    LOGGER.warn("RegionFileStorage adapter could not resolve storage fields; falling back to direct storage access.");
                }
                return null;
            }
            @SuppressWarnings("unchecked")
            final Long2ObjectLinkedOpenHashMap<RegionFile> regionCache = (Long2ObjectLinkedOpenHashMap<RegionFile>)map;
            return new StorageAccess(folder, info, sync, maxCache, regionCache);
        } catch (final ReflectiveOperationException ignored) {
            if (ACCESS_LOGGED.compareAndSet(false, true)) {
                LOGGER.warn("RegionFileStorage adapter failed to read storage fields; falling back to direct storage access.");
            }
            return null;
        }
    }

    private static RegionFile getRegionFileIfExistsInternal(final StorageAccess access, final int chunkX, final int chunkZ) throws IOException {
        final long key = ChunkPos.asLong(chunkX >> REGION_SHIFT, chunkZ >> REGION_SHIFT);

        RegionFile ret = access.regionCache.getAndMoveToFirst(key);
        if (ret != null) {
            return ret;
        }

        if (!doesRegionFilePossiblyExist(access, key)) {
            return null;
        }

        if (access.regionCache.size() >= access.maxCache) {
            access.regionCache.removeLast().close();
        }

        final Path regionPath = access.folder.resolve(getRegionFileName(chunkX, chunkZ));
        if (!Files.exists(regionPath)) {
            markNonExisting(access, key);
            return null;
        }

        createRegionFile(access, key);
        FileUtil.createDirectoriesSafe(access.folder);

        ret = new RegionFile(access.info, regionPath, access.folder, access.sync);
        access.regionCache.putAndMoveToFirst(key, ret);

        return ret;
    }

    private static RegionFile getRegionFileInternal(final StorageAccess access, final int chunkX, final int chunkZ) throws IOException {
        final long key = ChunkPos.asLong(chunkX >> REGION_SHIFT, chunkZ >> REGION_SHIFT);

        RegionFile ret = access.regionCache.getAndMoveToFirst(key);
        if (ret != null) {
            return ret;
        }

        if (access.regionCache.size() >= access.maxCache) {
            access.regionCache.removeLast().close();
        }

        final Path regionPath = access.folder.resolve(getRegionFileName(chunkX, chunkZ));

        createRegionFile(access, key);
        FileUtil.createDirectoriesSafe(access.folder);

        ret = new RegionFile(access.info, regionPath, access.folder, access.sync);
        access.regionCache.putAndMoveToFirst(key, ret);

        return ret;
    }

    private static boolean doesRegionFilePossiblyExist(final StorageAccess access, final long position) {
        if (access.nonExistingRegionFiles.contains(position)) {
            access.nonExistingRegionFiles.addAndMoveToFirst(position);
            return false;
        }
        return true;
    }

    private static void createRegionFile(final StorageAccess access, final long position) {
        access.nonExistingRegionFiles.remove(position);
    }

    private static void markNonExisting(final StorageAccess access, final long position) {
        if (access.nonExistingRegionFiles.addAndMoveToFirst(position)) {
            while (access.nonExistingRegionFiles.size() >= MAX_NON_EXISTING_CACHE) {
                access.nonExistingRegionFiles.removeLastLong();
            }
        }
    }

    private static String getRegionFileName(final int chunkX, final int chunkZ) {
        return "r." + (chunkX >> REGION_SHIFT) + "." + (chunkZ >> REGION_SHIFT) + ".mca";
    }

    private static void resetAccessCache(final StorageAccess access, final ArrayIndexOutOfBoundsException error) {
        LOGGER.error("RegionFileStorage adapter cache corrupted; resetting cache", error);
        try {
            for (final RegionFile regionFile : access.regionCache.values()) {
                try {
                    regionFile.close();
                } catch (final IOException ignored) {
                    // best-effort cleanup
                }
            }
        } catch (final Throwable ignored) {
            // cache structure may already be corrupted
        }
        try {
            access.regionCache.clear();
        } catch (final Throwable ignored) {
            // ignore secondary failures
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

    private static Field findField(final String name, final Class<?> type, final boolean isStatic) {
        try {
            final Field field = RegionFileStorage.class.getDeclaredField(name);
            if (field.getType() == type && Modifier.isStatic(field.getModifiers()) == isStatic) {
                field.setAccessible(true);
                return field;
            }
        } catch (final ReflectiveOperationException ignored) {
            // fall back to type-based lookup
        }
        return findFieldByType(type, isStatic);
    }

    private static Field findFieldByType(final Class<?> type, final boolean isStatic) {
        Field match = null;
        for (final Field field : RegionFileStorage.class.getDeclaredFields()) {
            if (field.getType() != type || Modifier.isStatic(field.getModifiers()) != isStatic) {
                continue;
            }
            if (match != null) {
                match.setAccessible(true);
                return match;
            }
            match = field;
        }
        if (match != null) {
            match.setAccessible(true);
        }
        return match;
    }

    private static final class StorageAccess {
        private final Path folder;
        private final RegionStorageInfo info;
        private final boolean sync;
        private final int maxCache;
        private final Long2ObjectLinkedOpenHashMap<RegionFile> regionCache;
        private final LongLinkedOpenHashSet nonExistingRegionFiles = new LongLinkedOpenHashSet();

        private StorageAccess(final Path folder, final RegionStorageInfo info, final boolean sync, final int maxCache,
                              final Long2ObjectLinkedOpenHashMap<RegionFile> regionCache) {
            this.folder = folder;
            this.info = info;
            this.sync = sync;
            this.maxCache = maxCache;
            this.regionCache = regionCache;
        }
    }
}
