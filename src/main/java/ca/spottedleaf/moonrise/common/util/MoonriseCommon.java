package ca.spottedleaf.moonrise.common.util;

public final class MoonriseCommon {

    // Compatibility field for AsyncYouer/Paper integrations expecting this exact signature.
    public static final ca.spottedleaf.concurrentutil.executor.standard.PrioritisedThreadPool WORKER_POOL = null;

    public static final long WORKER_QUEUE_HOLD_TIME = MoonriseCommonInternal.WORKER_QUEUE_HOLD_TIME;
    public static final long IO_QUEUE_HOLD_TIME = MoonriseCommonInternal.IO_QUEUE_HOLD_TIME;
    public static final int CLIENT_DIVISION = MoonriseCommonInternal.CLIENT_DIVISION;
    public static final int SERVER_DIVISION = MoonriseCommonInternal.SERVER_DIVISION;

    public static void adjustWorkerThreads(final int configWorkerThreads, final int configIoThreads) {
        MoonriseCommonInternal.adjustWorkerThreads(configWorkerThreads, configIoThreads);
    }

    public static void haltExecutors() {
        MoonriseCommonInternal.haltExecutors();
    }

    private MoonriseCommon() {}
}
