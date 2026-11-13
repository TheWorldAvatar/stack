package com.cmclinnovations.stack.clients.ontop;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.jena.rdf.model.Model;
import org.eclipse.rdf4j.sparqlbuilder.core.query.ConstructQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cmclinnovations.stack.clients.blazegraph.BlazegraphClient;
import com.cmclinnovations.stack.clients.core.ClientWithEndpoint;
import com.cmclinnovations.stack.clients.core.StackClient;
import com.cmclinnovations.stack.clients.core.datasets.CopyDatasetQuery;
import com.cmclinnovations.stack.clients.utils.JsonHelper;
import com.cmclinnovations.stack.clients.utils.SparqlRulesFile;
import com.cmclinnovations.stack.clients.utils.TempFile;
import com.fasterxml.jackson.databind.ObjectMapper;

import it.unibz.inf.ontop.dbschema.impl.json.JsonLens;
import it.unibz.inf.ontop.dbschema.impl.json.JsonLenses;

public class OntopClient extends ClientWithEndpoint<OntopEndpointConfig> {

    protected static final Logger LOGGER = LoggerFactory.getLogger(OntopClient.class);

    public static final String ONTOP_MAPPING_FILE = "ONTOP_MAPPING_FILE";
    public static final String ONTOP_ONTOLOGY_FILE = "ONTOP_ONTOLOGY_FILE";
    public static final String ONTOP_SPARQL_RULES_FILE = "ONTOP_SPARQL_RULES_FILE";
    public static final String ONTOP_LENSES_FILE = "ONTOP_LENSES_FILE";

    private static Map<String, OntopClient> instances = new HashMap<>();

    public static OntopClient getInstance(String containerName) {
        return instances.computeIfAbsent(containerName, OntopClient::new);
    }

    private OntopClient(String containerName) {
        super(containerName, OntopEndpointConfig.class);
    }

    public void uploadOntology(String catalogNamespace, List<String> ontologyDatasets) {
        ConstructQuery query = CopyDatasetQuery.getConstructQuery(ontologyDatasets);

        Model model = BlazegraphClient.getInstance().getRemoteStoreClient(catalogNamespace)
                .executeConstruct(query.getQueryString());

        writeTurtleToFile(model);
    }

    public void updateOBDA(Path newMappingFilePath) {
        String containerId = StackClient.isRunningInKubernetes() ? null : getContainerId(getContainerName());
        Path ontopMappingFilePath = getFilePath(containerId, ONTOP_MAPPING_FILE);

        try {
            SQLPPMappingImplementation mapping = new SQLPPMappingImplementation();

            if (fileExistsAtRuntime(containerId, ontopMappingFilePath)) {

                if (null == newMappingFilePath) {
                    // A mapping file already exists and no new one has been passed to be added.
                    return;
                }
                try (TempFile localTempOntopMappingFilePath = SQLPPMappingImplementation
                        .createTempOBDAFile(ontopMappingFilePath);
                        OutputStream outputStream = Files.newOutputStream(localTempOntopMappingFilePath.getPath())) {
                    outputStream.write(readFileContent(containerId, ontopMappingFilePath));
                    mapping.addMappings(localTempOntopMappingFilePath.getPath());
                }
            }

            if (null != newMappingFilePath) {
                mapping.addMappings(newMappingFilePath);
            }
            try (TempFile localTempOntopMappingFilePath = SQLPPMappingImplementation
                    .createTempOBDAFile(ontopMappingFilePath)) {
                mapping.serialize(localTempOntopMappingFilePath.getPath());

                writeFileContent(containerId, ontopMappingFilePath,
                    Files.readAllBytes(localTempOntopMappingFilePath.getPath()));
            }
        } catch (IOException ex) {
            throw new RuntimeException(
                    "Failed to write out combined Ontop mapping file '" + ontopMappingFilePath + "'.", ex);
        }
    }

    public void uploadRules(List<Path> ruleFiles) {
        String containerId = StackClient.isRunningInKubernetes() ? null : getContainerId(getContainerName());
        Path sparqlRulesFilePath = getFilePath(containerId, ONTOP_SPARQL_RULES_FILE);
        SparqlRulesFile sparqlRules = new SparqlRulesFile(ruleFiles);

        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            sparqlRules.write(outputStream);
            writeFileContent(containerId, sparqlRulesFilePath, outputStream.toByteArray());
        } catch (IOException ex) {
            throw new RuntimeException(
                    "Failed to write SPARQL Rules file.", ex);
        }
    }

    public void uploadLenses(List<Path> lensesFiles) {
        String containerId = StackClient.isRunningInKubernetes() ? null : getContainerId(getContainerName());
        Path lensesFilePath = getFilePath(containerId, ONTOP_LENSES_FILE);
        List<JsonLens> mergedRelations = new ArrayList<>();

        ObjectMapper mapper = JsonHelper.getMapper();
        for (Path lensesFile : lensesFiles) {
            try {
                JsonLenses jsonLenses = mapper.readValue(lensesFile.toFile(), JsonLenses.class);
                mergedRelations.addAll(jsonLenses.relations);
            } catch (IOException e) {
                throw new RuntimeException("Failed to read lenses from file: '" + lensesFile + "'.\n", e);
            }
        }

        JsonLenses mergedLenses = new JsonLenses(mergedRelations);

        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            mapper.writeValue(outputStream, mergedLenses);
            writeFileContent(containerId, lensesFilePath, outputStream.toByteArray());
        } catch (IOException ex) {
            throw new RuntimeException(
                    "Failed to write lenses file.", ex);
        }
    }

    private void writeTurtleToFile(Model model) {
        String containerId = StackClient.isRunningInKubernetes() ? null : getContainerId(getContainerName());
        Path ontopOntologyFilePath = getFilePath(containerId, ONTOP_ONTOLOGY_FILE);

        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            model.write(outputStream, "TURTLE");
            writeFileContent(containerId, ontopOntologyFilePath, outputStream.toByteArray());
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }

    }

    private Path getFilePath(String containerId, String filenameKey) {
        if (StackClient.isRunningInKubernetes()) {
            String filePath = System.getenv(filenameKey);
            if (null != filePath && !filePath.isBlank()) {
                return Path.of(filePath);
            }
            throw new RuntimeException("Environment variable '" + filenameKey
                    + "' is not set for the Kubernetes uploader runtime.");
        }
        return getEnvironmentVariable(containerId, filenameKey)
                .map(Path::of)
                .orElseThrow(() -> new RuntimeException("Environment variable '" + filenameKey
                        + " not set through Docker for '" + getContainerName() + "' container."));
    }

    private boolean fileExistsAtRuntime(String containerId, Path filePath) {
        return StackClient.isRunningInKubernetes()
                ? Files.exists(filePath)
                : fileExists(containerId, filePath.toString());
    }

    private byte[] readFileContent(String containerId, Path filePath) throws IOException {
        return StackClient.isRunningInKubernetes()
                ? Files.readAllBytes(filePath)
                : retrieveFile(containerId, filePath.toString());
    }

    private void writeFileContent(String containerId, Path filePath, byte[] content) throws IOException {
        if (StackClient.isRunningInKubernetes()) {
            Path parent = filePath.getParent();
            if (null != parent) {
                Files.createDirectories(parent);
            }
            Files.write(filePath, content);
        } else {
            sendFileContent(containerId, filePath, content);
        }
    }

}
