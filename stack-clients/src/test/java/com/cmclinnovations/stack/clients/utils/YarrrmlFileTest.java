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
                 Assertions.assertEquals(new HashMap<>(), yarrrmlFile.getRules());
        }

        @Test
        void testFileConstructor() throws IOException, URISyntaxException {
                Path rulesFilePath = Paths.get(YarrrmlFileTest.class.getResource(TEST_ONE_FILE_NAME).toURI());
                YarrrmlFile yarrrmlFile = new YarrrmlFile(rulesFilePath, TEST_ENDPOINT);
                 Assertions.assertEquals(this.genExpectedYarrrmlContents(rulesFilePath.toString()), yarrrmlFile.getRules());
        }

        @Test
        void testAddRules_SuccessOriginalFormat() throws IOException, URISyntaxException {
                Path rulesFilePath = Paths.get(YarrrmlFileTest.class.getResource(TEST_ONE_FILE_NAME).toURI());
                YarrrmlFile yarrrmlFile = new YarrrmlFile();
                yarrrmlFile.addRules(rulesFilePath, TEST_ENDPOINT);
                 Assertions.assertEquals(this.genExpectedYarrrmlContents(rulesFilePath.toString()), yarrrmlFile.getRules());
        }

        @Test
        void testAddRules_SuccessShortcutFormat() throws IOException, URISyntaxException {
                Path rulesFilePath = Paths.get(YarrrmlFileTest.class.getResource(TEST_TWO_FILE_NAME).toURI());
                YarrrmlFile yarrrmlFile = new YarrrmlFile();
                yarrrmlFile.addRules(rulesFilePath, TEST_ENDPOINT);
                 Assertions.assertEquals(this.genExpectedYarrrmlContents(rulesFilePath.toString()), yarrrmlFile.getRules());
        }

        @Test
        void testAddRules_SuccessFunctionFormat() throws IOException, URISyntaxException {
                Path rulesFilePath = Paths.get(YarrrmlFileTest.class.getResource(TEST_THREE_FILE_NAME).toURI());
                YarrrmlFile yarrrmlFile = new YarrrmlFile();
                yarrrmlFile.addRules(rulesFilePath, TEST_ENDPOINT);
                 Assertions.assertEquals(this.genExpectedYarrrmlFunctionContents(rulesFilePath.toString()),
                                yarrrmlFile.getRules());
        }

        @Test
        void testAddRules_SuccessFunctionShorcutFormat() throws IOException, URISyntaxException {
                Path rulesFilePath = Paths.get(YarrrmlFileTest.class.getResource(TEST_FOUR_FILE_NAME).toURI());
                YarrrmlFile yarrrmlFile = new YarrrmlFile();
                yarrrmlFile.addRules(rulesFilePath, TEST_ENDPOINT);
                 Assertions.assertEquals(this.genExpectedYarrrmlFunctionContents(rulesFilePath.toString()),
                                yarrrmlFile.getRules());
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
