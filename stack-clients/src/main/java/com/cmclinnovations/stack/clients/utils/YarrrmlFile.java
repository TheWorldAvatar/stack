package com.cmclinnovations.stack.clients.utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

public final class YarrrmlFile {
    private String fileName;
    private Map<String, Object> rules;

    private final Yaml yaml;
    private final Map<String, Object> sourcesTemplate;
    private final Map<String, Object> targetTemplate;

    private static final String SOURCES_KEY = "sources";
    private static final String SOURCE_REF_KEY = "source-ref";
    private static final String TARGETS_KEY = "targets";
    private static final String TARGET_REF_KEY = "target-ref";
    private static final String MAPPING_KEY = "mappings";
    private static final String SUBJECT_KEY = "s";
    private static final String PRED_OBJ_KEY = "po";
    private static final String ACCESS_KEY = "access";

    /**
     * Constructor to initialise all variables.
     */
    public YarrrmlFile() {
        final DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        this.fileName = "";
        this.yaml = new Yaml(options);
        this.rules = new HashMap<>();
        this.sourcesTemplate = new HashMap<>();
        this.targetTemplate = new HashMap<>();
        this.genYamlTemplates(this.sourcesTemplate, this.targetTemplate);
    }

    /**
     * Constructor to construct the YARRRML rules from the file input.
     * 
     * @param ymlFile  YML file path.
     * @param endpoint The endpoint url for uploading the converted triples.
     */
    public YarrrmlFile(URI ymlFile, String endpoint) throws IOException {
        this();
        this.addRules(ymlFile, endpoint);
    }

    /**
     * Add YARRRML rules.
     * 
     * @param ymlFile  YML file path.
     * @param endpoint The endpoint url for uploading the converted triples.
     */
    public void addRules(URI ymlFile, String endpoint) throws IOException {
        Path ymlFilePath = Paths.get(ymlFile);
        this.rules = yaml.load(Files.newInputStream(ymlFilePath));
        this.appendSources(this.rules, ymlFilePath.getFileName());
        this.appendTargets(this.rules, endpoint);
        this.updateMappings(this.rules);
    }

    /**
     * Get the file name.
     */
    public String getFileName() {
        return this.fileName;
    }

    /**
     * Writes the file content into a byte array for further usage.
     */
    public byte[] write() throws IOException {
        // yaml dump is returning {} instead of ""
        if (this.rules.isEmpty()) {
            return new byte[0];
        }
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                Writer writer = new OutputStreamWriter(outputStream)) {
            yaml.dump(this.rules, writer);
            return outputStream.toByteArray();
        } catch (IOException e) {
            throw new IOException(MessageFormat.format("Failed to write YARRRML output. {0}", e.getMessage()));
        }
    }

    /**
     * Generate YAML templates for csv sources and SPARQL targets that can be reused
     * and added to different outputs.
     * 
     * @param sources The map for storing the sources template.
     * @param targets The map for storing the targets template.
     */
    private void genYamlTemplates(Map<String, Object> sources, Map<String, Object> targets) {
        // For the sources template
        Map<String, Object> nestedSourceRef = new HashMap<>();
        nestedSourceRef.put("referenceFormulation", "csv");
        sources.put(SOURCE_REF_KEY, nestedSourceRef);
        // For the targets template
        Map<String, Object> sparql = new HashMap<>();
        sparql.put("type", "sd");
        sparql.put("serialization", "turtle");
        targets.put(TARGET_REF_KEY, sparql);
    }

    /**
     * Appends the sources to the output yaml object.
     * 
     * @param output   The target YAML output.
     * @param filePath The file path to the file source.
     */
    private void appendSources(Map<String, Object> output, Path filePath) {
        this.fileName = filePath.toString();

        Map<String, Object> sources = new HashMap<>(this.sourcesTemplate);
        Map<String, Object> sourceRef = this.castToMapStringObject(sources.get(SOURCE_REF_KEY));
        sourceRef.put(ACCESS_KEY, "/data/" + FileUtils.replaceExtension(this.fileName, "csv"));
        output.put(SOURCES_KEY, sources);
    }

    /**
     * Appends the targets to the output yaml object.
     * 
     * @param output   The target YAML output.
     * @param endpoint The target endpoint.
     */
    private void appendTargets(Map<String, Object> output, String endpoint) {
        Map<String, Object> targets = new HashMap<>(this.targetTemplate);
        Map<String, Object> targetRef = this.castToMapStringObject(targets.get(TARGET_REF_KEY));
        targetRef.put(ACCESS_KEY, endpoint);
        output.put(TARGETS_KEY, targets);
    }

    /**
     * Update the mappings with sources and targets information along with
     * transforming the output format into a compliant YARRRML format.
     * 
     * @param output The target YAML output.
     */
    private void updateMappings(Map<String, Object> output) {
        Map<String, Object> ruleMappings = this.castToMapStringObject(output.get(MAPPING_KEY));
        ruleMappings.forEach((field, value) -> {
            // Addition of sources and their reference
            Map<String, Object> mappingValue = this.castToMapStringObject(value);
            mappingValue.put(SOURCES_KEY, SOURCE_REF_KEY);

            // Addition of targets and their reference
            Object subjectVal = mappingValue.get(SUBJECT_KEY);
            if (subjectVal instanceof String) {
                Map<String, Object> newSubjectMap = new HashMap<>();
                newSubjectMap.put("value", subjectVal);
                newSubjectMap.put(TARGETS_KEY, TARGET_REF_KEY);
                mappingValue.put(SUBJECT_KEY, newSubjectMap);
            } else if (subjectVal instanceof Map<?, ?>) {
                Map<String, Object> stringObjectMap = this.castToMapStringObject(subjectVal);
                stringObjectMap.put(TARGETS_KEY, TARGET_REF_KEY);
            }

            // Transformation of po if necessary to be YARRRML compliant
            // SnakeYAML transforms the YML content for nested lists into - -
            // BUT YARRRML parser only accepts -[] as a shortcut and should be updated
            // accordingly
            List<Map<String, String>> transformedPo = new ArrayList<>();
            List<Object> originalPo = this.castToListObject(mappingValue.get(PRED_OBJ_KEY));
            for (Object predObj : originalPo) {
                if (predObj instanceof List) {
                    List<Object> nestedPredObjList = this.castToListObject(predObj);
                    if (nestedPredObjList.size() == 2 && nestedPredObjList.get(0) instanceof String
                            && nestedPredObjList.get(1) instanceof String) {
                        Map<String, String> transformedItem = new HashMap<>();
                        transformedItem.put("p", nestedPredObjList.get(0).toString());
                        transformedItem.put("o", nestedPredObjList.get(1).toString());
                        transformedPo.add(transformedItem);
                    }
                }
            }
            if (!transformedPo.isEmpty()) {
                mappingValue.put(PRED_OBJ_KEY, transformedPo);
            }
        });
    }

    private Map<String, Object> castToMapStringObject(Object obj) throws ClassCastException {
        if (obj instanceof Map<?, ?>) {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) obj;
            return result;
        }
        throw new IllegalArgumentException("Invalid input. Object is not a Map.");
    }

    private List<Object> castToListObject(Object obj) throws ClassCastException {
        if (obj instanceof List<?>) {
            @SuppressWarnings("unchecked")
            List<Object> result = (List<Object>) obj;
            return result;
        }
        throw new IllegalArgumentException("Invalid input. Object is not a list.");
    }
}
