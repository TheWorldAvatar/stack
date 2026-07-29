package com.cmclinnovations.stack.clients.gdal;

public enum GdalExecutionMode {
    DOCKER,
    LOCAL;

    static GdalExecutionMode parseOrDefault(String rawValue) {
        if (null == rawValue || rawValue.isBlank()) {
            return DOCKER;
        }

        switch (rawValue.trim().toLowerCase()) {
            case "docker":
                return DOCKER;
            case "local":
                return LOCAL;
            default:
                return DOCKER;
        }
    }
}
