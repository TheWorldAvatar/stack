package com.cmclinnovations.stack.clients.utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

class YarrrmlFileTest {
    private static final String TEST_ENDPOINT = "https://example.org/kg/sparql";
    private static final String TEST_ONE_FILE_NAME = "rules.yml";
    private static final String TEST_TWO_FILE_NAME = "rules2.yml";

    @Test
    void testDefaultConstructor() throws IOException {
        YarrrmlFile yarrrmlFile = new YarrrmlFile();
        String expected = "";
        checkRules(yarrrmlFile, expected, expected);
    }

    @Test
    void testFileConstructor() throws IOException, URISyntaxException {
        URI rulesFilePath = YarrrmlFileTest.class.getResource(TEST_ONE_FILE_NAME).toURI();
        YarrrmlFile yarrrmlFile = new YarrrmlFile(rulesFilePath, TEST_ENDPOINT);
        checkRules(yarrrmlFile, TEST_ONE_FILE_NAME, this.genExpectedYarrrmlContents(TEST_ONE_FILE_NAME));
    }

    @Test
    void testAddRules_SuccessOriginalFormat() throws IOException, URISyntaxException {
        URI rulesFilePath = YarrrmlFileTest.class.getResource(TEST_ONE_FILE_NAME).toURI();
        YarrrmlFile yarrrmlFile = new YarrrmlFile();
        yarrrmlFile.addRules(rulesFilePath, TEST_ENDPOINT);
        checkRules(yarrrmlFile, TEST_ONE_FILE_NAME, this.genExpectedYarrrmlContents(TEST_ONE_FILE_NAME));
    }

    @Test
    void testAddRules_SuccessShortcutFormat() throws IOException, URISyntaxException {
        URI rulesFilePath = YarrrmlFileTest.class.getResource(TEST_TWO_FILE_NAME).toURI();
        YarrrmlFile yarrrmlFile = new YarrrmlFile();
        yarrrmlFile.addRules(rulesFilePath, TEST_ENDPOINT);
        checkRules(yarrrmlFile, TEST_TWO_FILE_NAME, this.genExpectedYarrrmlContents(TEST_TWO_FILE_NAME));
    }

    private void checkRules(YarrrmlFile yarrrmlFile, String expectedFileName, String expectedContents)
            throws IOException {
        Assertions.assertEquals(expectedFileName, yarrrmlFile.getFileName());
        Assertions.assertEquals(expectedContents, new String(yarrrmlFile.write(), StandardCharsets.UTF_8));
    }

    private String genExpectedYarrrmlContents(String fileName) throws IOException {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        Yaml yaml = new Yaml(options);
        Map<String, Object> yamlData = new LinkedHashMap<>();

        Map<String, String> prefixes = new LinkedHashMap<>();
        prefixes.put("rdfs", "http://www.w3.org/2000/01/rdf-schema#");
        prefixes.put("base", "https://theworldavatar.io/kg/");
        yamlData.put("prefixes", prefixes);

        Map<String, Map<String, Object>> mappings = new LinkedHashMap<>();

        Map<String, Object> personMapping = new LinkedHashMap<>();
        Map<String, Object> personS = new LinkedHashMap<>();
        personS.put("value", "base:person/$(id)");
        personS.put("targets", "target-ref");
        personMapping.put("s", personS);

        List<Map<String, Object>> personPO = new ArrayList<>();
        this.genExpectedYarrrmlPredObj(personPO, "a", "base:Person");
        this.genExpectedYarrrmlPredObj(personPO, "base:hasName", "base:person/name/$(id)~iri");
        personMapping.put("po", personPO);

        personMapping.put("sources", "source-ref");
        mappings.put("person", personMapping);

        Map<String, Object> personNameMapping = new LinkedHashMap<>();
        Map<String, Object> personNameS = new LinkedHashMap<>();
        personNameS.put("value", "base:person/name/$(id)");
        personNameS.put("targets", "target-ref");
        personNameMapping.put("s", personNameS);

        List<Map<String, Object>> personNamePO = new ArrayList<>();
        this.genExpectedYarrrmlPredObj(personNamePO, "a", "base:PersonName");
        this.genExpectedYarrrmlPredObj(personNamePO, "rdfs:label", "$(name)");
        personNameMapping.put("po", personNamePO);

        personNameMapping.put("sources", "source-ref");
        mappings.put("person-name", personNameMapping);

        yamlData.put("mappings", mappings);

        Map<String, Map<String, Object>> sources = new LinkedHashMap<>();
        Map<String, Object> sourceRef = new LinkedHashMap<>();
        sourceRef.put("referenceFormulation", "csv");
        sourceRef.put("access", FileUtils.replaceExtension(fileName, "csv"));
        sources.put("source-ref", sourceRef);
        yamlData.put("sources", sources);

        Map<String, Map<String, Object>> targets = new LinkedHashMap<>();
        Map<String, Object> targetRef = new LinkedHashMap<>();
        targetRef.put("serialization", "turtle");
        targetRef.put("access", "https://example.org/kg/sparql");
        targetRef.put("type", "sd");
        targets.put("target-ref", targetRef);
        yamlData.put("targets", targets);

        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                Writer writer = new OutputStreamWriter(outputStream)) {
            yaml.dump(yamlData, writer);
            return outputStream.toString();
        }
    }

    private void genExpectedYarrrmlPredObj(List<Map<String, Object>> output, String predVal, String objVal) {
        Map<String, Object> predObjMap = new LinkedHashMap<>();
        predObjMap.put("p", predVal);
        predObjMap.put("o", objVal);
        output.add(predObjMap);
    }
}
