package com.cmclinnovations.stack.clients.gdal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cmclinnovations.stack.clients.core.StackClient;

public final class GdalExecutionConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(GdalExecutionConfig.class);

    private GdalExecutionConfig() {
    }

    public static GdalExecutionMode getMode() {
        String rawValue = System.getenv(StackClient.GDAL_EXECUTION_MODE_KEY);
        GdalExecutionMode mode = GdalExecutionMode.parseOrDefault(rawValue);

        if (null != rawValue && !rawValue.isBlank()) {
            String normalized = rawValue.trim().toLowerCase();
            if (!"docker".equals(normalized) && !"local".equals(normalized)) {
                LOGGER.warn("Invalid {} value '{}', defaulting to '{}'.",
                        StackClient.GDAL_EXECUTION_MODE_KEY,
                        rawValue,
                        GdalExecutionMode.DOCKER.name().toLowerCase());
            }
        }

        return mode;
    }
}
