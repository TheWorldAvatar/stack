package com.cmclinnovations.stack.clients.utils;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class YarrrmlFileTest {
        private static final String TEST_ENDPOINT = "https://example.org/kg/sparql";
        private static final String TEST_ONE_FILE_NAME = "rules.yml";
        private static final String TEST_TWO_FILE_NAME = "rules2.yml";
        private static final String TEST_THREE_FILE_NAME = "rules_functions.yml";
        private static final String TEST_FOUR_FILE_NAME = "rules2_functions.yml";

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

        @Test
        void testAddRules_SuccessFunctionFormat() throws IOException, URISyntaxException {
                Path rulesFilePath = Paths.get(YarrrmlFileTest.class.getResource(TEST_THREE_FILE_NAME).toURI());
                YarrrmlFile yarrrmlFile = new YarrrmlFile();
                yarrrmlFile.addRules(rulesFilePath, TEST_ENDPOINT);
                checkRules(yarrrmlFile, TEST_THREE_FILE_NAME,
                                this.genExpectedYarrrmlFunctionContents(TEST_ONE_FILE_NAME));
        }

        @Test
        void testAddRules_SuccessFunctionShorcutFormat() throws IOException, URISyntaxException {
                Path rulesFilePath = Paths.get(YarrrmlFileTest.class.getResource(TEST_FOUR_FILE_NAME).toURI());
                YarrrmlFile yarrrmlFile = new YarrrmlFile();
                yarrrmlFile.addRules(rulesFilePath, TEST_ENDPOINT);
                checkRules(yarrrmlFile, TEST_FOUR_FILE_NAME,
                                this.genExpectedYarrrmlFunctionContents(TEST_ONE_FILE_NAME));
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
                                        Assertions.assertEquals(expectedRules.containsKey(key), rules.containsKey(key),
                                                        "Expected rules is missing the key: " + key);
                                        Object expectedObj = expectedRules.get(key);
                                        Object actualObj = rules.get(key);
                                        if (expectedObj instanceof Map<?, ?> && actualObj instanceof Map<?, ?>) {
                                                this.checkMappings((Map<?, ?>) expectedObj, (Map<?, ?>) actualObj);
                                        } else if (expectedObj instanceof List<?> && actualObj instanceof List<?>) {
                                                List<Object> expectedList = (List<Object>) expectedObj;
                                                List<Object> actualList = (List<Object>) actualObj;
                                                for (int i = 0; i < actualList.size(); i++) {
                                                        if (actualList.get(i) instanceof Map<?, ?>)
                                                                this.checkMappings((Map<?, ?>) expectedList.get(i),
                                                                                (Map<?, ?>) actualList.get(i));
                                                }
                                        } else {
                                                Assertions.assertEquals(expectedObj, actualObj,
                                                                "Expected Object does not match Actual Object for: "
                                                                                + key);
                                        }
                                });
        }

        private Map<String, Object> genExpectedYarrrmlContents(String fileName) {
                Map<String, Object> yamlData = this.genExpectedYarrrmlTemplate(fileName);

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
                return yamlData;
        }

        private Map<String, Object> genExpectedYarrrmlFunctionContents(String fileName) {
                Map<String, Object> yamlData = this.genExpectedYarrrmlTemplate(fileName);
                Map<String, Map<String, Object>> mappings = new HashMap<>();

                mappings.put("person", Map.of(
                                "sources", "source-ref",
                                "s", this.genExpectedYarrrmlFunction(
                                                "grel:string_trim",
                                                "base:person/$(id)~iri",
                                                Map.of("targets", "target-ref")),
                                "po", List.of(this.genExpectedYarrrmlPredObj("a", "base:Person"),
                                                this.genExpectedYarrrmlPredObj("base:hasName",
                                                                this.genExpectedYarrrmlFunction(
                                                                                "grel:toLowerCase",
                                                                                this.genExpectedYarrrmlFunction(
                                                                                                "grel:string_trim",
                                                                                                "base:department/name/$(name)~iri"))))));
                yamlData.put("mappings", mappings);
                return yamlData;
        }

        private Map<String, Object> genExpectedYarrrmlTemplate(String fileName) {
                Map<String, Object> yamlData = new AliasMap<>();
                yamlData.put("prefixes", Map.of(
                                "rdfs", "http://www.w3.org/2000/01/rdf-schema#",
                                "base", "https://theworldavatar.io/kg/",
                                "grel", "http://users.ugent.be/~bjdmeest/function/grel.ttl#"));
                yamlData.put("sources", Map.of(
                                "source-ref", Map.of(
                                                "referenceFormulation", "csv",
                                                "access", FileUtils.replaceExtension(fileName, "csv"))));
                yamlData.put("targets", Map.of(
                                "target-ref", Map.of(
                                                "serialization", "turtle",
                                                "access", TEST_ENDPOINT,
                                                "type", "sd")));
                return yamlData;
        }

        private Map<String, Object> genExpectedYarrrmlPredObj(String predVal, Object objVal) {
                return Map.of("p", predVal,
                                "o", objVal);
        }

        private Map<String, Object> genExpectedYarrrmlFunction(String function, Object paramVal) {
                return this.genExpectedYarrrmlFunction(function, paramVal, null);
        }

        private Map<String, Object> genExpectedYarrrmlFunction(String function, Object paramVal,
                        Map<String, Object> additionalFields) {
                Map<String, Object> result = new HashMap<>();
                result.put("function", function);
                result.put("parameters", List.of(
                                Map.of("parameter", "grel:valueParameter",
                                                "value", paramVal)));
                if (additionalFields != null && !additionalFields.isEmpty()) {
                        result.putAll(additionalFields);
                }
                return result;
        }
}
