package com.cmclinnovations.stack.clients.utils;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.google.common.base.Objects;

class YarrrmlFileTest {
        private static final String TEST_ENDPOINT = "https://example.org/kg/sparql";
        private static final String TEST_ONE_FILE_NAME = "rules.yml";
        private static final String TEST_TWO_FILE_NAME = "rules2.yml";

        @Test
        void testDefaultConstructor() {
                YarrrmlFile yarrrmlFile = new YarrrmlFile();
                String expected = "";
                checkRules(yarrrmlFile, expected, new HashMap<>());
        }

        @Test
        void testFileConstructor() throws IOException, URISyntaxException {
                Path rulesFilePath = Paths.get(YarrrmlFileTest.class.getResource(TEST_ONE_FILE_NAME).toURI());
                YarrrmlFile yarrrmlFile = new YarrrmlFile(rulesFilePath, TEST_ENDPOINT);
                checkRules(yarrrmlFile, TEST_ONE_FILE_NAME, this.genExpectedYarrrmlContents(TEST_ONE_FILE_NAME));
        }

        @Test
        void testAddRules_SuccessOriginalFormat() throws IOException, URISyntaxException {
                Path rulesFilePath = Paths.get(YarrrmlFileTest.class.getResource(TEST_ONE_FILE_NAME).toURI());
                YarrrmlFile yarrrmlFile = new YarrrmlFile();
                yarrrmlFile.addRules(rulesFilePath, TEST_ENDPOINT);
                checkRules(yarrrmlFile, TEST_ONE_FILE_NAME, this.genExpectedYarrrmlContents(TEST_ONE_FILE_NAME));
        }

        @Test
        void testAddRules_SuccessShortcutFormat() throws IOException, URISyntaxException {
                Path rulesFilePath = Paths.get(YarrrmlFileTest.class.getResource(TEST_TWO_FILE_NAME).toURI());
                YarrrmlFile yarrrmlFile = new YarrrmlFile();
                yarrrmlFile.addRules(rulesFilePath, TEST_ENDPOINT);
                checkRules(yarrrmlFile, TEST_TWO_FILE_NAME, this.genExpectedYarrrmlContents(TEST_TWO_FILE_NAME));
        }

        private void checkRules(YarrrmlFile yarrrmlFile, String expectedFileName,
                        Map<String, Object> expectedContents) {
                Assertions.assertEquals(expectedFileName, yarrrmlFile.getFileName());
                this.checkMappings(expectedContents, yarrrmlFile.getRules());
        }

        private void checkMappings(Map<?, ?> expectedRules, Map<?, ?> rules) {
                Assertions.assertEquals(expectedRules.isEmpty(), rules.isEmpty());
                rules.keySet().stream()
                                .forEach(key -> {
                                        System.out.println(key);
                                        System.out.println(expectedRules.containsKey(key));
                                        Assertions.assertEquals(expectedRules.containsKey(key), rules.containsKey(key),
                                                        "Expected rules is missing the key: " + key);
                                        Object expectedObj = expectedRules.get(key);
                                        Object actualObj = rules.get(key);
                                        if (expectedObj instanceof Map<?, ?> && actualObj instanceof Map<?, ?>) {
                                                this.checkMappings((Map<?, ?>) expectedObj, (Map<?, ?>) actualObj);
                                        } else {
                                                Assertions.assertEquals(expectedObj, actualObj,
                                                                "Expected Object does not match Actual Object for: "
                                                                                + key);
                                        }
                                });
        }

        private Map<String, Object> genExpectedYarrrmlContents(String fileName) {
                Map<String, Object> yamlData = new AliasMap<>();

                yamlData.put("prefixes", Map.of(
                                "rdfs", "http://www.w3.org/2000/01/rdf-schema#",
                                "base", "https://theworldavatar.io/kg/"));

                Map<String, Map<String, Object>> mappings = new HashMap<>();

                mappings.put("person", Map.of(
                                "sources", "source-ref",
                                "s", Map.of(
                                                "value", "base:person/$(id)",
                                                "targets", "target-ref"),
                                "po", List.of(this.genExpectedYarrrmlPredObj("a", "base:Person"),
                                                this.genExpectedYarrrmlPredObj("base:hasName",
                                                                "base:person/name/$(id)~iri"))));

                mappings.put("person-name", Map.of(
                                "sources", "source-ref",
                                "s", Map.of(
                                                "value", "base:person/name/$(id)",
                                                "targets", "target-ref"),
                                "po", List.of(this.genExpectedYarrrmlPredObj("a", "base:PersonName"),
                                                this.genExpectedYarrrmlPredObj("rdfs:label", "$(name)"))));

                yamlData.put("mappings", mappings);

                yamlData.put("sources", Map.of(
                                "source-ref", Map.of(
                                                "referenceFormulation", "csv",
                                                "access", FileUtils.replaceExtension(fileName, "csv"))));
                yamlData.put("targets", Map.of(
                                "target-ref", Map.of(
                                                "serialization", "turtle",
                                                "access", "https://example.org/kg/sparql",
                                                "type", "sd")));
                return yamlData;
        }

        private Map<String, Object> genExpectedYarrrmlPredObj(String predVal,
                        String objVal) {
                return Map.of("p", predVal,
                                "o", objVal);
        }
}
