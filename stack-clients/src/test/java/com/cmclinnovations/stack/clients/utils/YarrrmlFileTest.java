package com.cmclinnovations.stack.clients.utils;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class YarrrmlFileTest {
        private static final String TEST_ENDPOINT = "https://example.org/kg/sparql";
        private static final String TEST_ONE_FILE_NAME = "yml/test/rules.yml";
        private static final String EXPECTED_ONE_FILE_NAME = "yml/expected/rules.yml";
        private static final String TEST_TWO_FILE_NAME = "yml/test/rules2.yml";
        private static final String EXPECTED_TWO_FILE_NAME = "yml/expected/rules2.yml";
        private static final String TEST_THREE_FILE_NAME = "yml/test/rules_functions.yml";
        private static final String EXPECTED_THREE_FILE_NAME = "yml/expected/rules_functions.yml";
        private static final String TEST_FOUR_FILE_NAME = "yml/test/rules2_functions.yml";
        private static final String EXPECTED_FOUR_FILE_NAME = "yml/expected/rules2_functions.yml";
        private static final String TEST_FIVE_FILE_NAME = "yml/test/rules_condition.yml";
        private static final String EXPECTED_FIVE_FILE_NAME = "yml/expected/rules_condition.yml";

        @Test
        void testDefaultConstructor() {
                YarrrmlFile yarrrmlFile = new YarrrmlFile();
                Assertions.assertEquals(new HashMap<>(), yarrrmlFile.getRules());
        }

        @Test
        void testFileConstructor() throws IOException, URISyntaxException {
                Path rulesFilePath = Paths.get(YarrrmlFileTest.class.getResource(TEST_ONE_FILE_NAME).toURI());
                YarrrmlFile yarrrmlFile = new YarrrmlFile(rulesFilePath, TEST_ENDPOINT);
                Assertions.assertEquals(
                                this.genExpectedYarrrmlContents(
                                                YarrrmlFileTest.class.getResource(EXPECTED_ONE_FILE_NAME),
                                                rulesFilePath, TEST_ENDPOINT),
                                yarrrmlFile.getRules());
        }

        @Test
        void testAddRules_SuccessOriginalFormat() throws IOException, URISyntaxException {
                Path rulesFilePath = Paths.get(YarrrmlFileTest.class.getResource(TEST_ONE_FILE_NAME).toURI());
                YarrrmlFile yarrrmlFile = new YarrrmlFile();
                yarrrmlFile.addRules(rulesFilePath, TEST_ENDPOINT);
                Assertions.assertEquals(this.genExpectedYarrrmlContents(
                                YarrrmlFileTest.class.getResource(EXPECTED_ONE_FILE_NAME),
                                rulesFilePath, TEST_ENDPOINT),
                                yarrrmlFile.getRules());
        }

        @Test
        void testAddRules_SuccessShortcutFormat() throws IOException, URISyntaxException {
                Path rulesFilePath = Paths.get(YarrrmlFileTest.class.getResource(TEST_TWO_FILE_NAME).toURI());
                YarrrmlFile yarrrmlFile = new YarrrmlFile();
                yarrrmlFile.addRules(rulesFilePath, TEST_ENDPOINT);
                Assertions.assertEquals(this.genExpectedYarrrmlContents(
                                YarrrmlFileTest.class.getResource(EXPECTED_TWO_FILE_NAME),
                                rulesFilePath, TEST_ENDPOINT),
                                yarrrmlFile.getRules());
        }

        @Test
        void testAddRules_SuccessFunctionFormat() throws IOException, URISyntaxException {
                Path rulesFilePath = Paths.get(YarrrmlFileTest.class.getResource(TEST_THREE_FILE_NAME).toURI());
                YarrrmlFile yarrrmlFile = new YarrrmlFile();
                yarrrmlFile.addRules(rulesFilePath, TEST_ENDPOINT);
                Assertions.assertEquals(this.genExpectedYarrrmlContents(
                                YarrrmlFileTest.class.getResource(EXPECTED_THREE_FILE_NAME),
                                rulesFilePath, TEST_ENDPOINT),
                                yarrrmlFile.getRules());
        }

        @Test
        void testAddRules_SuccessFunctionShorcutFormat() throws IOException, URISyntaxException {
                Path rulesFilePath = Paths.get(YarrrmlFileTest.class.getResource(TEST_FOUR_FILE_NAME).toURI());
                YarrrmlFile yarrrmlFile = new YarrrmlFile();
                yarrrmlFile.addRules(rulesFilePath, TEST_ENDPOINT);
                Assertions.assertEquals(this.genExpectedYarrrmlContents(
                                YarrrmlFileTest.class.getResource(EXPECTED_FOUR_FILE_NAME),
                                rulesFilePath, TEST_ENDPOINT),
                                yarrrmlFile.getRules());
        }

        @Test
        void testAddRules_SuccessConditionFormat() throws IOException, URISyntaxException {
                Path rulesFilePath = Paths.get(YarrrmlFileTest.class.getResource(TEST_FIVE_FILE_NAME).toURI());
                YarrrmlFile yarrrmlFile = new YarrrmlFile();
                yarrrmlFile.addRules(rulesFilePath, TEST_ENDPOINT);
                Assertions.assertEquals(this.genExpectedYarrrmlContents(
                                YarrrmlFileTest.class.getResource(EXPECTED_FIVE_FILE_NAME),
                                rulesFilePath, TEST_ENDPOINT),
                                yarrrmlFile.getRules());
        }

        private AliasMap<Object> genExpectedYarrrmlContents(URL expectedFilePath, Path expectedSourceLocation,
                        String endpoint)
                        throws IOException, URISyntaxException {
                Yaml yaml = new Yaml();
                String content = new String(Files.readAllBytes(Paths.get(expectedFilePath.toURI())));
                String modifiedContent = content
                                .replace("[source]",
                                                FileUtils.replaceExtension(expectedSourceLocation.toString(), "csv"))
                                .replace("[target]", endpoint);

                Map<String, Object> loadedMap = yaml.load(modifiedContent);

                AliasMap<Object> rules = new AliasMap<>();
                rules.putAll(loadedMap);
                return rules;
        }
}
