package dev.lukebemish.taskgraphrunner.model.conversion;

import oshi.SystemInfo;

class SystemSpecsFinder {
    static long recommendedThreads() {
        return Runtime.getRuntime().availableProcessors() / 2;
    }

    static long recommendedMemory() {
        return Math.min(TOTAL_MEMORY / 5, GIGABYTE * 4);
    }

    private static final long TOTAL_MEMORY;

    private static final long GIGABYTE = 1024 * 1024 * 1024;

    static {
        var systemInfo = new SystemInfo();
        var hardware = systemInfo.getHardware();
        TOTAL_MEMORY = hardware.getMemory().getTotal();
    }
}
