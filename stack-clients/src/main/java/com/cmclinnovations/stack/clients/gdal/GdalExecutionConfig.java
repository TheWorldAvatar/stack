package com.cmclinnovations.stack.clients.gdal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cmclinnovations.stack.clients.core.StackClient;

public final class GdalExecutionConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(GdalExecutionConfig.class);

    private GdalExecutionConfig() {
    }

    public static long resolveCommandTimeoutSeconds(String rawValue, long defaultValue) {
        if (null == rawValue || rawValue.isBlank()) {
            return defaultValue;
        }

        try {
            long parsedValue = Long.parseLong(rawValue.trim());
            if (parsedValue > 0) {
                return parsedValue;
            }
        } catch (NumberFormatException ex) {
            LOGGER.warn("Invalid {} value '{}', using default '{}' seconds.",
                    StackClient.GDAL_COMMAND_TIMEOUT_KEY, rawValue, defaultValue);
        }

        return defaultValue;
    }
}