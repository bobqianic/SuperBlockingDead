package com.example;

final class PathSearchBudget {
    private final long deadlineNanos;
    private boolean expired;

    PathSearchBudget(long maxNanos) {
        this.deadlineNanos = System.nanoTime() + maxNanos;
    }

    boolean expired() {
        if (expired) {
            return true;
        }

        if (System.nanoTime() - deadlineNanos >= 0L) {
            expired = true;
            return true;
        }

        return false;
    }
}
