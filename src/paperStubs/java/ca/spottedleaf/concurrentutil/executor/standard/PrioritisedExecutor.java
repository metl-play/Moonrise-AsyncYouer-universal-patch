package ca.spottedleaf.concurrentutil.executor.standard;

public interface PrioritisedExecutor {

    enum Priority {
        COMPLETING(0),
        BLOCKING(1),
        HIGHEST(2),
        HIGHER(3),
        HIGH(4),
        NORMAL(5),
        LOW(6),
        LOWER(7),
        LOWEST(8),
        IDLE(9);

        public static final int TOTAL_PRIORITIES = values().length;
        public static final int TOTAL_SCHEDULABLE_PRIORITIES = TOTAL_PRIORITIES;
        public final int priority;

        Priority(final int priority) {
            this.priority = priority;
        }
    }
}
