package com.cmclinnovations.stack.clients.ontop;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.jena.rdf.model.Model;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFParser;
import org.eclipse.rdf4j.rio.RDFWriter;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.sparqlbuilder.core.query.ConstructQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cmclinnovations.stack.clients.blazegraph.BlazegraphClient;
import com.cmclinnovations.stack.clients.core.ClientWithEndpoint;
import com.cmclinnovations.stack.clients.core.datasets.CopyDatasetQuery;
import com.cmclinnovations.stack.clients.utils.JsonHelper;
import com.cmclinnovations.stack.clients.utils.SparqlRulesFile;
import com.cmclinnovations.stack.clients.utils.TempFile;
import com.fasterxml.jackson.databind.ObjectMapper;

import it.unibz.inf.ontop.dbschema.impl.json.JsonLens;
import it.unibz.inf.ontop.dbschema.impl.json.JsonLenses;

public class OntopClient extends ClientWithEndpoint<OntopEndpointConfig> {

    // List of RDF formats supported as listed here:
    // https://ontop-vkg.org/guide/cli.html#ontop-endpoint
    private static final List<RDFFormat> ALLOWED_RULES_FORMATS = List.of(
            RDFFormat.RDFXML, RDFFormat.TURTLE,
            RDFFormat.NTRIPLES, RDFFormat.NQUADS,
            RDFFormat.TRIG, RDFFormat.JSONLD);

    protected static final Logger LOGGER = LoggerFactory.getLogger(OntopClient.class);

    public static final String ONTOP_MAPPING_FILE = "ONTOP_MAPPING_FILE";
    public static final String ONTOP_ONTOLOGY_FILE = "ONTOP_ONTOLOGY_FILE";
    public static final String ONTOP_SPARQL_RULES_FILE = "ONTOP_SPARQL_RULES_FILE";
    public static final String ONTOP_LENSES_FILE = "ONTOP_LENSES_FILE";
    public static final String ONTOP_FACTS_FILE = "ONTOP_FACTS_FILE";

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
        String containerId = getContainerId(getContainerName());
        Path ontopMappingFilePath = getFilePath(containerId, ONTOP_MAPPING_FILE);

        try {
            SQLPPMappingImplementation mapping = new SQLPPMappingImplementation();

            if (fileExists(containerId, ontopMappingFilePath.toString())) {

                if (null == newMappingFilePath) {
                    // A mapping file already exists and no new one has been passed to be added.
                    return;
                }
                try (TempFile localTempOntopMappingFilePath = SQLPPMappingImplementation
                        .createTempOBDAFile(ontopMappingFilePath);
                        OutputStream outputStream = Files.newOutputStream(localTempOntopMappingFilePath.getPath())) {
                    outputStream.write(retrieveFile(containerId, ontopMappingFilePath.toString()));
                    mapping.addMappings(localTempOntopMappingFilePath.getPath());
                }
            }

            if (null != newMappingFilePath) {
                mapping.addMappings(newMappingFilePath);
            }
            try (TempFile localTempOntopMappingFilePath = SQLPPMappingImplementation
                    .createTempOBDAFile(ontopMappingFilePath)) {
                mapping.serialize(localTempOntopMappingFilePath.getPath());

                sendFileContent(containerId, ontopMappingFilePath,
                        Files.readAllBytes(localTempOntopMappingFilePath.getPath()));
            }
        } catch (IOException ex) {
            throw new RuntimeException(
                    "Failed to write out combined Ontop mapping file '" + ontopMappingFilePath + "'.", ex);
        }
    }

    public void uploadRules(List<Path> ruleFiles) {
        String containerId = getContainerId(getContainerName());
        Path sparqlRulesFilePath = getFilePath(containerId, ONTOP_SPARQL_RULES_FILE);
        SparqlRulesFile sparqlRules = new SparqlRulesFile(ruleFiles);

        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            sparqlRules.write(outputStream);
            sendFileContent(containerId, sparqlRulesFilePath, outputStream.toByteArray());
        } catch (IOException ex) {
            throw new RuntimeException(
                    "Failed to write SPARQL Rules file.", ex);
        }
    }

    public void uploadLenses(List<Path> lensesFiles) {
        String containerId = getContainerId(getContainerName());
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
            sendFileContent(containerId, lensesFilePath, outputStream.toByteArray());
        } catch (IOException ex) {
            throw new RuntimeException(
                    "Failed to write lenses file.", ex);
        }
    }

    public void uploadFacts(List<Path> factsFiles) {
        String containerId = getContainerId(getContainerName());
        Path ontopFactsFilePath = getFilePath(containerId, ONTOP_FACTS_FILE);
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {

            // Add the ontology triples as facts, if present.
            String ontopOntologyFilePath = getFilePath(containerId, ONTOP_ONTOLOGY_FILE).toString();
            try {
                if (fileExists(containerId, ontopOntologyFilePath)) {
                    outputStream.write(retrieveFile(containerId, ontopOntologyFilePath));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(
                        "Failed to read Ontop ontology as a facts file: " + ontopOntologyFilePath, e);
            }

            RDFWriter turtleWriter = Rio.createWriter(RDFFormat.TURTLE, outputStream);
            for (Path factsFile : factsFiles) {
                RDFParser rdfParser = Rio.createParser(RDFFormat
                        .matchFileName(factsFile.getFileName().toString(), ALLOWED_RULES_FORMATS)
                        .orElseThrow(() -> new RuntimeException("Unsupported RDF format for file: " + factsFile)));
                rdfParser.setRDFHandler(turtleWriter);
                try (InputStream inputStream = new FileInputStream(factsFile.toFile())) {
                    rdfParser.parse(inputStream);
                } catch (MalformedURLException e) {
                    throw new UncheckedIOException("Malformed URL for Ontop facts file: " + factsFile, e);
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to read Ontop facts file: " + factsFile, e);
                }
            }
            sendFileContent(containerId, ontopFactsFilePath, outputStream.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write Ontop facts file: " + ontopFactsFilePath, e);
        }

    }

    private void writeTurtleToFile(Model model) {
        String containerId = getContainerId(getContainerName());
        Path ontopOntologyFilePath = getFilePath(containerId, ONTOP_ONTOLOGY_FILE);

        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            model.write(outputStream, "TURTLE");
            sendFileContent(containerId, ontopOntologyFilePath, outputStream.toByteArray());
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }

    }

    private Path getFilePath(String containerId, String filenameKey) {
        return getEnvironmentVariable(containerId, filenameKey)
                .map(Path::of)
                .orElseThrow(() -> new RuntimeException("Environment variable '" + filenameKey
                        + " not set through Docker for '" + getContainerName() + "' container."));
    }

}
