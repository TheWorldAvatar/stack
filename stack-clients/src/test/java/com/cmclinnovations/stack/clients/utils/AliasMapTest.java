package com.cmclinnovations.stack.clients.utils;

import static org.junit.Assert.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AliasMapTest {
        private static final String PRIMARY_KEY = "primaryKey";
        private static final String PRIMARY_ALT_KEY = "primalias";
        private static final String PRIMARY_ALT_TWO_KEY = "primalias2";
        private static final Integer PRIMARY_VAL = 10;
        private static final Integer PRIMARY_ALT_VAL = 30;
        private static final String SECONDARY_KEY = "secondarykey";
        private static final String SECONDARY_ALT_KEY = "secalias";
        private static final Integer SECONDARY_VAL = 20;

        private AliasMap<Integer> aliasMap;

        @BeforeEach
        void setUp() {
                aliasMap = new AliasMap<>();
                aliasMap.put(PRIMARY_KEY, PRIMARY_VAL);
                aliasMap.put(SECONDARY_KEY, SECONDARY_VAL);
        }

        @Test
        void testContainsKey_WithAliases() {
                assertTrue(aliasMap.containsKey(PRIMARY_KEY, PRIMARY_ALT_KEY, PRIMARY_ALT_TWO_KEY));
                assertTrue(aliasMap.containsKey(SECONDARY_KEY, SECONDARY_ALT_KEY));
        }

        @Test
        void testContainsKey_WithUnaddedAliasKeys() {
                // These aliases were not added and should not be available
                assertFalse(aliasMap.containsKey(PRIMARY_ALT_KEY, PRIMARY_ALT_TWO_KEY));
                assertFalse(aliasMap.containsKey(SECONDARY_ALT_KEY));
        }

        @Test
        void testContainsKey_WithNullOrEmptyAliases() {
                assertFalse(aliasMap.containsKey((String[]) null));
                assertFalse(aliasMap.containsKey());
        }

        @Test
        void testGet_WithAliases() {
                assertEquals(PRIMARY_VAL, aliasMap.get(PRIMARY_KEY, PRIMARY_ALT_KEY, PRIMARY_ALT_TWO_KEY));
                assertEquals(SECONDARY_VAL, aliasMap.get(SECONDARY_KEY, SECONDARY_ALT_KEY));
        }

        @Test
        void testGet_WithoutAliasesValues() {
                // These aliases were not added and should not be available
                assertNull(aliasMap.get(PRIMARY_ALT_KEY, PRIMARY_ALT_TWO_KEY));
                assertNull(aliasMap.get(SECONDARY_ALT_KEY));
        }

        @Test
        void testGet_WithNullOrEmptyAliases() {
                assertNull(aliasMap.get((String[]) null));
                assertNull(aliasMap.get());
        }

        @Test
        void testPut_WithAliases() {
                aliasMap.put(PRIMARY_ALT_KEY, PRIMARY_ALT_VAL, PRIMARY_KEY, PRIMARY_ALT_TWO_KEY);
                // Check added key only
                assertTrue(aliasMap.containsKey(PRIMARY_ALT_KEY));
                assertEquals(PRIMARY_ALT_VAL, aliasMap.get(PRIMARY_ALT_KEY));
                // Check added key with aliases
                assertTrue(aliasMap.containsKey(PRIMARY_KEY, PRIMARY_ALT_KEY, PRIMARY_ALT_TWO_KEY));
                assertEquals(PRIMARY_ALT_VAL, aliasMap.get(PRIMARY_KEY, PRIMARY_ALT_KEY, PRIMARY_ALT_TWO_KEY));

                // Test that old aliases are removed
                assertFalse(aliasMap.containsKey(PRIMARY_KEY, PRIMARY_ALT_TWO_KEY));
                assertNull(aliasMap.get(PRIMARY_KEY, PRIMARY_ALT_TWO_KEY));
        }
}
