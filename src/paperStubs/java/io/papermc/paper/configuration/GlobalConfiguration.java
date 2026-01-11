package io.papermc.paper.configuration;

public class GlobalConfiguration {
    public static final class ChunkSystem {
        public int ioThreads = -1;
        public int workerThreads = -1;
        public String genParallelism = "default";
    }
}
