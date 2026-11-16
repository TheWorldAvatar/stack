package com.cmclinnovations.stack.clients.gdal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GdalExecutionConfigTest {

    @Test
    void resolvesConfiguredTimeoutSeconds() {
        assertEquals(1800L, GdalExecutionConfig.resolveCommandTimeoutSeconds("1800", 300L));
    }

    @Test
    void fallsBackToDefaultForBlankOrInvalidValues() {
        assertEquals(300L, GdalExecutionConfig.resolveCommandTimeoutSeconds("", 300L));
        assertEquals(300L, GdalExecutionConfig.resolveCommandTimeoutSeconds("abc", 300L));
        assertEquals(300L, GdalExecutionConfig.resolveCommandTimeoutSeconds("0", 300L));
    }
}