package com.cmclinnovations.stack.clients.rml;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cmclinnovations.stack.clients.blazegraph.BlazegraphEndpointConfig;
import com.cmclinnovations.stack.clients.core.EndpointNames;
import com.cmclinnovations.stack.clients.docker.ContainerClient;
import com.cmclinnovations.stack.clients.utils.FileUtils;
import com.cmclinnovations.stack.clients.utils.TempDir;
import com.cmclinnovations.stack.clients.utils.YarrrmlFile;

public class RmlMapperClient extends ContainerClient {
  private static final Logger LOGGER = LoggerFactory.getLogger(RmlMapperClient.class);

  private static final String CSV_FILE_EXTENSION = "csv";
  private static final String YML_FILE_EXTENSION = "yml";
  private static final String TTL_FILE_EXTENSION = "ttl";
  private static final String YARRRML_PARSER_EXECUTABLE_PATH = "/app/bin/parser.js";

  private static RmlMapperClient instance = null;

  public static RmlMapperClient getInstance() {
    if (null == instance) {
      instance = new RmlMapperClient();
    }
    return instance;
  }

  private RmlMapperClient() {
  }

  /**
   * Parses YARRRML files into RDF triples that will be uploaded at the target
   * namespace.
   * 
   * @param dirPath   Target directory path.
   * @param namespace Target namespace to upload the converted RDF triples.
   */
  public void parseYarrrmlToRDF(Path dirPath, String namespace) {
    LOGGER.info("Checking and parsing YARRRML files...");
    this.validateDirContents(dirPath);
    try (TempDir tmpDir = makeLocalTempDir()) {
      // Copy all csv and rml files into the temp directory
      tmpDir.copyFrom(dirPath);
      Map<String, String> rmlRules = this.genRmlRules(tmpDir, namespace);
      this.convertToRDF(tmpDir, rmlRules);
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
      throw new IllegalArgumentException(
          MessageFormat.format("Detected missing file pairs with {0} csv and {1} yml files! Ensure files are even.",
              csvFiles.size(), ymlFiles.size()));
    }
    Set<String> csvFileNames = csvFiles.stream()
        .map(csvFile -> {
          try {
            return FileUtils.getFileNameWithoutExtension(csvFile.toURL());
          } catch (MalformedURLException ex) {
            LOGGER.error("Invalid URL: {}. Read error message for more information: {}", csvFile, ex.getMessage());
            throw new IllegalArgumentException(MessageFormat.format("Invalid URL: {0}", csvFile));
          }
        }).collect(Collectors.toSet());

    ymlFiles.forEach(ymlFile -> {
      try {
        String ymlFileName = FileUtils.getFileNameWithoutExtension(ymlFile.toURL());
        if (!csvFileNames.contains(ymlFileName)) {
          LOGGER.error("CSV file is missing for: {}.", ymlFileName);
          throw new IllegalArgumentException(MessageFormat.format("CSV file is missing for: {0}.", ymlFileName));
        }
      } catch (MalformedURLException ex) {
        LOGGER.error("Invalid URL: {}. Read error message for more information: {}", ymlFile, ex.getMessage());
        throw new IllegalArgumentException(MessageFormat.format("Invalid URL: {0}", ymlFile));
      }
    });
  }

  /**
   * Generate RML rules from YARRRML files in the temporary directory if
   * available.
   * 
   * @param tmpDir    Target temporary directory.
   * @param namespace Target namespace to upload the converted RDF triples.
   */
  private Map<String, String> genRmlRules(TempDir tmpDir, String namespace) {
    LOGGER.info("Retrieving the target SPARQL endpoint...");
    BlazegraphEndpointConfig blazegraphConfig = readEndpointConfig(EndpointNames.BLAZEGRAPH,
        BlazegraphEndpointConfig.class);
    String sparqlEndpoint = blazegraphConfig.getUrl(namespace);

    LOGGER.info("Converting the YARRRML inputs into RML rules...");
    String containerId = super.getContainerId(EndpointNames.RML);
    Collection<URI> ymlFiles = this.getFiles(tmpDir.getPath(), YML_FILE_EXTENSION);

    Map<String, String> rmlRules = new HashMap<>();
    ymlFiles.forEach(ymlFile -> {
      ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
      ByteArrayOutputStream errorStream = new ByteArrayOutputStream();
      try {
        String fileName = FileUtils.getFileNameWithoutExtension(ymlFile.toURL());
        LOGGER.info("Updating YARRRML rules with sources and targets for {}...", fileName);
        // Generate file path for the parsed YML file
        YarrrmlFile yarrrmlFile = new YarrrmlFile(Paths.get(ymlFile), sparqlEndpoint);
        Path parsedYmlPath = Files.writeString(Paths.get(ymlFile), yarrrmlFile.write());

        LOGGER.info("Generating RML rules for {}...", fileName);
        String execId = super.createComplexCommand(containerId, YARRRML_PARSER_EXECUTABLE_PATH, "-i",
            parsedYmlPath.toString())
            .withOutputStream(outputStream)
            .withErrorStream(errorStream)
            .exec();
        super.handleErrors(errorStream, execId, LOGGER);
        rmlRules.put(fileName, outputStream.toString(StandardCharsets.UTF_8));
      } catch (IOException e) {
        LOGGER.error(containerId, e);
        throw new UncheckedIOException(e);
      }
    });
    return rmlRules;
  }

  /**
   * Parses the RML rules into RDF triples that will be uploaded at the target
   * namespace.
   * 
   * @param tmpDir   Target temporary directory.
   * @param rmlRules Input RML rules.
   */
  private void convertToRDF(TempDir tmpDir, Map<String, String> rmlRules) {
    LOGGER.info("Uploading the csv files using the RML rules into the target endpoint...");
    String rmlMapperJavaContainerId = super.getContainerId(EndpointNames.RML_JAVA);

    rmlRules.forEach((fileName, content) -> {
      ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
      ByteArrayOutputStream errorStream = new ByteArrayOutputStream();
      try {
        Path tmpRmlFilePath = Files.createTempFile(tmpDir.getPath(), fileName, "." + TTL_FILE_EXTENSION);
        Files.writeString(tmpRmlFilePath, content);
        LOGGER.info("Executing RML rules for {}...", fileName);

        String execId = super.createComplexCommand(rmlMapperJavaContainerId, "java", "-jar", "/rmlmapper.jar", "-m",
            tmpRmlFilePath.toString(), "-s", "turtle")
            .withOutputStream(outputStream)
            .withErrorStream(errorStream)
            .exec();
        super.handleErrors(errorStream, execId, LOGGER);
      } catch (IOException e) {
        LOGGER.error(rmlMapperJavaContainerId, e);
        throw new UncheckedIOException(e);
      }
    });
  }

  /**
   * Retrieves the files in the target directory.
   * 
   * @param dirPath       Target directory path.
   * @param fileExtension The file extension of interest.
   */
  private Collection<URI> getFiles(Path dirPath, String fileExtension) {
    try {
      LOGGER.info("Getting all {} files from directory {} ...", fileExtension, dirPath.toAbsolutePath());
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
