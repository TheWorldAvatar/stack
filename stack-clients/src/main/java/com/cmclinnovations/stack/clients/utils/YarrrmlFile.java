package com.cmclinnovations.stack.clients.utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

public final class YarrrmlFile {
    private AliasMap<Object> rules;

    private final Yaml yaml;
    private final Map<String, Object> sourcesTemplate;

    private static final String SOURCES_KEY = "sources";
    private static final String SOURCE_REF_KEY = "source-ref";
    private static final String MAPPING_KEY = "mappings";
    private static final String MAPPING_ALT_KEY = "mapping";
    private static final String MAPPING_ALT_TWO_KEY = "m";
    private static final String SUBJECT_KEY = "s";
    private static final String SUBJECT_ALT_KEY = "subject";
    private static final String SUBJECT_ALT_TWO_KEY = "subjects";
    private static final String PRED_OBJ_KEY = "po";
    private static final String PRED_OBJ_ALT_KEY = "predicateobjects";
    private static final String OBJ_KEY = "o";
    private static final String OBJ_ALT_KEY = "objects";
    private static final String OBJ_ALT_TWO_KEY = "object";
    private static final String PARAMS_KEY = "parameters";
    private static final String PARAMS_ALT_KEY = "pms";
    private static final String ACCESS_KEY = "access";
    private static final String CONDITION_KEY = "condition";
    private static final String VALUE_KEY = "value";

    /**
     * Constructor to initialise all variables.
     */
    public YarrrmlFile() {
        final DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        this.yaml = new Yaml(options);
        this.rules = new AliasMap<>();
        this.sourcesTemplate = new HashMap<>();
        this.genYamlTemplates(this.sourcesTemplate);
    }

    /**
     * Constructor to construct the YARRRML rules from the file input.
     * 
     * @param ymlFile  YML file path.
     */
    public YarrrmlFile(Path ymlFile) throws IOException {
        this();
        this.addRules(ymlFile);
    }

    /**
     * Add YARRRML rules.
     * 
     * @param ymlFile  YML file path.
     */
    public void addRules(Path ymlFile) throws IOException {
        this.load(ymlFile);
        this.appendSources(this.rules, ymlFile);
        this.updateMappings(this.rules);
    }

    /**
     * Get the rules content.
     */
    public Map<String, Object> getRules() {
        return this.rules;
    }

    /**
     * Writes the file content into a byte array for further usage.
     */
    public String write() throws IOException {
        // yaml dump is returning {} instead of ""
        if (this.rules.isEmpty()) {
            return "";
        }
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                Writer writer = new OutputStreamWriter(outputStream)) {
            yaml.dump(this.rules, writer);
            return outputStream.toString(StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            throw new IOException(MessageFormat.format("Failed to write YARRRML output. {0}", e.getMessage()));
        }
    }

    /**
     * Load the YARRRML file as an Alias Map.
     * 
     * @param ymlFile YML file path.
     */
    public void load(Path ymlFile) throws IOException {
        Map<String, Object> loadedMap = yaml.load(Files.newInputStream(ymlFile));
        this.rules.putAll(loadedMap);
    }

    /**
     * Generate YAML templates for csv sources and SPARQL targets that can be reused
     * and added to different outputs.
     * 
     * @param sources The map for storing the sources template.
     */
    private void genYamlTemplates(Map<String, Object> sources) {
        // For the sources template
        Map<String, Object> nestedSourceRef = new HashMap<>();
        nestedSourceRef.put("referenceFormulation", "csv");
        sources.put(SOURCE_REF_KEY, nestedSourceRef);
    }

    /**
     * Appends the sources to the output yaml object.
     * 
     * @param output   The target YAML output.
     * @param filePath The file path to the file source.
     */
    private void appendSources(Map<String, Object> output, Path filePath) {
        Map<String, Object> sources = new HashMap<>(this.sourcesTemplate);
        Map<String, Object> sourceRef = this.castToAliasMap(sources.get(SOURCE_REF_KEY));
        sourceRef.put(ACCESS_KEY, FileUtils.replaceExtension(filePath.toString(), "csv"));
        sources.put(SOURCE_REF_KEY, sourceRef);
        output.put(SOURCES_KEY, sources);
    }

    /**
     * Update the mappings with sources and targets information along with
     * transforming the output format into a compliant YARRRML format.
     * 
     * @param output The target YAML output.
     */
    private void updateMappings(AliasMap<Object> output) {
        Map<String, Object> ruleMappings = this
                .castToAliasMap(output.get(MAPPING_KEY, MAPPING_ALT_KEY, MAPPING_ALT_TWO_KEY));
        ruleMappings.forEach((field, value) -> {
            // Addition of sources and their reference
            AliasMap<Object> mappingValue = this.castToAliasMap(value);
            mappingValue.put(SOURCES_KEY, SOURCE_REF_KEY);

            // Appends the target and its reference to the subjects key under mappings
            Object subjectVal = mappingValue.get(SUBJECT_KEY, SUBJECT_ALT_KEY, SUBJECT_ALT_TWO_KEY);
            AliasMap<Object> newSubjectMap = new AliasMap<>();
            if (subjectVal instanceof String) {
                newSubjectMap.put(VALUE_KEY, subjectVal);
            } else if (subjectVal instanceof Map<?, ?>) {
                newSubjectMap = this.castToAliasMap(subjectVal);
                // Function may be present instead of the value key
                this.updateFunctionMappingsIfPresent(newSubjectMap);
            }
            mappingValue.put(SUBJECT_KEY, newSubjectMap, SUBJECT_ALT_KEY, SUBJECT_ALT_TWO_KEY);
            if (mappingValue.containsKey(PRED_OBJ_KEY, PRED_OBJ_ALT_KEY)) {
                mappingValue.put(PRED_OBJ_KEY, updatePredObjMappings(mappingValue), PRED_OBJ_ALT_KEY);
            }
            // The mapping itself may be conditional
            this.updateConditionMappings(mappingValue);
            ruleMappings.put(field, mappingValue);
        });
        output.put(MAPPING_KEY, ruleMappings, MAPPING_ALT_KEY, MAPPING_ALT_TWO_KEY);
    }

    private AliasMap<Object> castToAliasMap(Object obj) throws ClassCastException {
        if (obj instanceof Map<?, ?>) {
            @SuppressWarnings("unchecked")
            Map<String, Object> mapObj = (Map<String, Object>) obj;
            AliasMap<Object> result = new AliasMap<>();
            result.putAll(mapObj);
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

    /**
     * Updates predicate-object mappings within a YARRRML-compliant data structure.
     * 
     * This method corrects the representation of nested lists in predicate-object
     * mappings, addressing the discrepancy between SnakeYAML's output and the
     * YARRRML parser's expectations. Specifically, this method transforms
     * the SnakeYAML output to match the non-shortcut predicate-object syntax.
     * 
     * @param mappingObj The mappings field object.
     */
    private List<Object> updatePredObjMappings(AliasMap<Object> mappingObj) {
        List<Object> predObjList = this.castToListObject(
                mappingObj.get(PRED_OBJ_KEY, PRED_OBJ_ALT_KEY));
        for (int i = 0; i < predObjList.size(); i++) {
            Object predObj = predObjList.get(i);
            if (predObj instanceof List) {
                List<Object> nestedPredObjList = this.castToListObject(predObj);
                if (nestedPredObjList.size() == 2 && nestedPredObjList.get(0) instanceof String
                        && nestedPredObjList.get(1) instanceof String) {
                    Map<String, Object> transformedPredObj = new HashMap<>();
                    transformedPredObj.put("p", nestedPredObjList.get(0).toString());
                    transformedPredObj.put(OBJ_KEY, nestedPredObjList.get(1).toString());
                    predObjList.set(i, transformedPredObj);
                }
            } else {
                AliasMap<Object> currentPOMap = this.castToAliasMap(predObj);
                // If the objects value is a map
                Object currentObjectsObj = currentPOMap.get(OBJ_KEY, OBJ_ALT_KEY,
                        OBJ_ALT_TWO_KEY);
                if (currentObjectsObj instanceof Map<?, ?>) {
                    AliasMap<Object> currentObjectsMap = this.castToAliasMap(currentObjectsObj);
                    this.updateFunctionMappingsIfPresent(currentObjectsMap);
                } else if (currentObjectsObj instanceof List) {
                    List<Object> currentObjList = this.castToListObject(currentObjectsObj);
                    for (int j = 0; j < currentObjList.size(); j++) {
                        AliasMap<Object> objectNode = this.castToAliasMap(currentObjList.get(j));
                        this.updateConditionMappings(objectNode);
                        this.updateFunctionMappingsIfPresent(objectNode);
                        currentObjList.set(j, objectNode);
                    }
                    currentPOMap.put(OBJ_KEY, currentObjList, OBJ_ALT_KEY,
                            OBJ_ALT_TWO_KEY);
                }

                this.updateConditionMappings(currentPOMap);
            }
        }
        return predObjList;
    }

    /**
     * Updates the condition mappings, which will contain a function if present.
     * 
     * @param mapObj The mappings field object.
     */
    private void updateConditionMappings(AliasMap<Object> mapObj) {
        if (mapObj.containsKey(CONDITION_KEY)) {
            AliasMap<Object> conditionMap = this.castToAliasMap(mapObj.get(CONDITION_KEY));
            this.updateFunctionMappingsIfPresent(conditionMap);
        }
    }

    /**
     * Recursively updates function mappings within a YARRRML-compliant data
     * structure.
     * 
     * This method addresses a common issue where SnakeYAML, when parsing
     * nested lists, represents them using `--` instead of the YARRRML-expected
     * `-[]` shortcut for function definitions. Specifically, this method transforms
     * the SnakeYAML output to match the non-shortcut function syntax .
     * 
     * @param mapObj The mappings field object.
     */
    private void updateFunctionMappingsIfPresent(AliasMap<Object> mapObj) {
        // Only continue parsing if this is a function mapping
        if (mapObj.containsKey(PARAMS_KEY, PARAMS_ALT_KEY)) {
            List<Object> paramObjs = this
                    .castToListObject(mapObj.get(PARAMS_KEY, PARAMS_ALT_KEY));
            for (int i = 0; i < paramObjs.size(); i++) {
                Object paramObj = paramObjs.get(i);
                // Detected use of shortcut, transforming into long form
                if (paramObj instanceof List) {
                    List<Object> paramList = this.castToListObject(paramObj);
                    if (paramList.size() == 2) {
                        Map<String, Object> transformedParam = new HashMap<>();
                        transformedParam.put("parameter", paramList.get(0).toString());
                        transformedParam.put(VALUE_KEY, paramList.get(1).toString());
                        paramObjs.set(i, transformedParam);
                    }
                } else if (paramObj instanceof Map<?, ?>) {
                    Object functionParamValue = this.castToAliasMap(paramObj).get(VALUE_KEY);
                    // For nested functions
                    if (functionParamValue instanceof Map<?, ?>) {
                        this.updateFunctionMappingsIfPresent(this.castToAliasMap(functionParamValue));
                    }
                }
            }
        }
    }
}
