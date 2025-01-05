package dev.lukebemish.taskgraphrunner.model.conversion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import oshi.SystemInfo;

class SystemSpecsFinder {
    private static final Logger LOGGER = LoggerFactory.getLogger(SystemSpecsFinder.class);

    static long recommendedThreads() {
        return Runtime.getRuntime().availableProcessors() / 2;
    }

    static long recommendedMemory() {
        if (TOTAL_MEMORY == -1) {
            return GIGABYTE * 4;
        }
        return Math.min(TOTAL_MEMORY / 5, GIGABYTE * 4);
    }

    private static final long TOTAL_MEMORY;

    private static final long GIGABYTE = 1024 * 1024 * 1024;

    static {
        long totalMemory = -1;
        try {
            var systemInfo = new SystemInfo();
            var hardware = systemInfo.getHardware();
            totalMemory = hardware.getMemory().getTotal();
        } catch (Throwable e) {
            LOGGER.warn("Failed to get system specs; memory allocated to tasks may not be optimal", e);
        }
        TOTAL_MEMORY = totalMemory;
    }
}
