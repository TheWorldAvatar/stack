package com.cmclinnovations.stack.clients.rml;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cmclinnovations.stack.clients.blazegraph.BlazegraphEndpointConfig;
import com.cmclinnovations.stack.clients.core.ClientWithEndpoint;
import com.cmclinnovations.stack.clients.core.EndpointNames;
import com.cmclinnovations.stack.clients.utils.FileUtils;

import be.ugent.rml.Executor;
import be.ugent.rml.Utils;
import be.ugent.rml.records.RecordsFactory;
import be.ugent.rml.store.QuadStore;
import be.ugent.rml.store.QuadStoreFactory;
import be.ugent.rml.store.RDF4JStore;
import be.ugent.rml.term.NamedNode;

public class RmlMapperClient extends ClientWithEndpoint<RmlMapperEndpointConfig> {
  private static final Logger LOGGER = LoggerFactory.getLogger(RmlMapperClient.class);

  private static final String CSV_FILE_EXTENSION = "csv";
  private static final String YML_FILE_EXTENSION = "yml";
  private static final String YARRRML_PARSER_EXECUTABLE_PATH = "/app/bin/parser.js";

  private static RmlMapperClient instance = null;

  public static RmlMapperClient getInstance() {
    if (null == instance) {
      instance = new RmlMapperClient();
    }
    return instance;
  }

  private RmlMapperClient() {
    super(EndpointNames.RML, RmlMapperEndpointConfig.class);
  }

  /**
   * Parses YARRRML files into RML mappings in the specified directory.
   * 
   * @param dirPath Target directory path.
   */
  public ByteArrayOutputStream parseYarrrmlToRml(Path dirPath) {
    LOGGER.info("Checking and parsing YARRRML files...");
    this.validateDirContents(dirPath);
    return this.genRmlRules(dirPath);
  }

  /**
   * Parses the RML rules into RDF triples that will be uploaded at the target
   * namespace.
   * 
   * @param rmlRules  Input RML rules.
   * @param namespace Target namespace to upload the converted RDF triples.
   */
  public void parseRmlToRDF(InputStream rmlRules, String namespace) {
    LOGGER.info("Reading the RML rules...");
    BlazegraphEndpointConfig blazegraphConfig = super.readEndpointConfig(EndpointNames.BLAZEGRAPH,
        BlazegraphEndpointConfig.class);
    String sparqlEndpoint = blazegraphConfig.getUrl(namespace);
    try {
      // TO DO
      // TO DO
      // RML Rules should be parsed and added with sources and targets
      // Source:
      // :source_000 a rml:LogicalSource;
      // rdfs:label "source label";
      // rml:source "source.csv";
      // rml:referenceFormulation ql:CSV.
      // :map_mappingname_000 a rr:TriplesMap; #to id;do not add
      // rml:logicalSource :source_000.
      // Target:
      // :target_000 a rmlt:LogicalTarget;
      // rdfs:label "sparql";
      // rmlt:serialization formats:Turtle;
      // rmlt:target :sd_000.
      // :sd_000 a sd:Service;
      // sd:supportedLanguage sd:SPARQL11Update;
      // sd:endpoint <SPARQL Endpoint>.
      // :s_000 a rr:SubjectMap; #to id;do not add
      // rml:logicalTarget :target_000.

      QuadStore rmlStore = QuadStoreFactory.read(rmlRules);

      // To parse the data into records eg each csv row is a record
      // As we are interested only in local file access, base path is crucial to
      // determine the absolute path from the root. Mapping path is not required for
      // this simple case
      RecordsFactory factory = new RecordsFactory("/", "/");

      // Output store serialises beyond nquads, but is not needed when uploading
      // directly to Blazegraph
      QuadStore outputStore = new RDF4JStore();

      // Function agent is not required - the library will default to their base
      // functions
      Executor executor = new Executor(rmlStore, factory, outputStore,
          Utils.getBaseDirectiveTurtleOrDefault(rmlRules, "https://theworldavatar.io/kg/"), null);
      QuadStore result = executor.execute(null).get(new NamedNode("rmlmapper://default.store"));

      BufferedWriter out = new BufferedWriter(new OutputStreamWriter(System.out));
      result.write(out, "turtle");
    } catch (Exception e) {
      LOGGER.error(e.getMessage());
    }
  }

  /**
   * Validates if the specified directory contains only pairs for the data file
   * and their corresponding mappings.
   * 
   * @param dirPath Target directory path.
   */
  private void validateDirContents(Path dirPath) {
    Collection<URI> csvFiles = this.getFiles(dirPath, CSV_FILE_EXTENSION);
    Collection<URI> ymlFiles = this.getFiles(dirPath, YML_FILE_EXTENSION);
    if (csvFiles.size() != ymlFiles.size()) {
      LOGGER.error("Detected missing file pairs with {} csv and {} yml files! Ensure files are even.",
          csvFiles.size(), ymlFiles.size());
      throw new IllegalArgumentException();
    }
  }

  /**
   * Generate RML rules from YARRRML files in the directory if available.
   * 
   * @param dirPath Target directory path.
   */
  private ByteArrayOutputStream genRmlRules(Path dirPath) {
    LOGGER.info("Converting the YARRRML inputs into RML rules...");
    String containerId = super.getContainerId(super.getContainerName());
    Collection<URI> ymlFiles = this.getFiles(dirPath, YML_FILE_EXTENSION);
    List<String> commandList = new ArrayList<>(List.of(YARRRML_PARSER_EXECUTABLE_PATH));

    // Convert from URI to String and append them to command
    List<String> filePaths = ymlFiles.stream()
        .map(ymlFile -> {
          String fileName = Paths.get(ymlFile).getFileName().toString();
          commandList.add("-i");
          commandList.add("/data/" + fileName);
          return fileName;
        })
        .collect(Collectors.toList());
    // Send the files to a new data directory
    super.sendFiles(containerId, dirPath.toAbsolutePath().toString(), filePaths, "/data");

    // Execute the command and return the RML mappings as streams
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    ByteArrayOutputStream errorStream = new ByteArrayOutputStream();
    String execId = super.createComplexCommand(containerId, commandList.toArray(new String[0]))
        .withOutputStream(outputStream)
        .withErrorStream(errorStream)
        .exec();
    super.handleErrors(errorStream, execId, LOGGER);
    return outputStream;
  }

  /**
   * Retrieves the files in the target directory.
   * 
   * @param dirPath       Target directory path.
   * @param fileExtension The file extension of interest.
   */
  private Collection<URI> getFiles(Path dirPath, String fileExtension) {
    try {
      LOGGER.info(MessageFormat.format("Getting all {0} files from directory {1} ...", fileExtension,
          dirPath.toAbsolutePath()));
      URL dirUrl = dirPath.toUri().toURL();
      return FileUtils.listFiles(dirUrl, fileExtension);
    } catch (URISyntaxException | MalformedURLException ex) {
      LOGGER.error("Invalid directory path: {} . Read error message for more information: {}", dirPath,
          ex.getMessage());
      throw new IllegalArgumentException(MessageFormat.format("Invalid directory path: {0}", dirPath));
    } catch (IOException ex) {
      LOGGER.error(ex.getMessage());
      throw new RuntimeException(MessageFormat.format("Failed to list files in the directory: {0}", ex.getMessage()));
    }
  }
}
