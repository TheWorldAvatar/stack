package com.cmclinnovations.stack.clients.rml;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cmclinnovations.stack.clients.blazegraph.BlazegraphEndpointConfig;
import com.cmclinnovations.stack.clients.core.ClientWithEndpoint;
import com.cmclinnovations.stack.clients.core.EndpointNames;
import com.cmclinnovations.stack.clients.utils.FileUtils;
import com.cmclinnovations.stack.clients.utils.YarrrmlFile;

public class RmlMapperClient extends ClientWithEndpoint<RmlMapperEndpointConfig> {
  private static final Logger LOGGER = LoggerFactory.getLogger(RmlMapperClient.class);

  private static final String CSV_FILE_EXTENSION = "csv";
  private static final String YML_FILE_EXTENSION = "yml";
  private static final String TTL_FILE_EXTENSION = "ttl";
  private static final String YARRRML_PARSER_EXECUTABLE_PATH = "/app/bin/parser.js";
  private static final String TEMP_CONTAINER_DATA_DIR_PATH = "/dataset";

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
   * @param dirPath   Target directory path.
   * @param namespace Target namespace to upload the converted RDF triples.
   */
  public Map<String, byte[]> parseYarrrmlToRml(Path dirPath, String namespace) {
    LOGGER.info("Checking and parsing YARRRML files...");
    this.validateDirContents(dirPath);
    return this.genRmlRules(dirPath, namespace);
  }

  /**
   * Parses the RML rules into RDF triples that will be uploaded at the target
   * namespace.
   * 
   * @param dirPath  Target directory path.
   * @param rmlRules Input RML rules.
   */
  public void parseRmlToRDF(Path dirPath, Map<String, byte[]> rmlRules) {
    LOGGER.info("Uploading the RML rules and csv files into the target container...");
    String rmlMapperJavaContainerId = super.getContainerId(EndpointNames.RML_JAVA);

    List<String> csvFiles = rmlRules.keySet().stream()
        .map(file -> FileUtils.replaceExtension(file, CSV_FILE_EXTENSION))
        .collect(Collectors.toList());
    super.sendFiles(rmlMapperJavaContainerId, dirPath.toAbsolutePath().toString(), csvFiles,
        TEMP_CONTAINER_DATA_DIR_PATH);
    super.sendFilesContent(rmlMapperJavaContainerId, rmlRules, TEMP_CONTAINER_DATA_DIR_PATH);

    LOGGER.info("Converting and uploading csv data...");
    rmlRules.keySet().stream().forEach(file -> {
      ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
      ByteArrayOutputStream errorStream = new ByteArrayOutputStream();
      LOGGER.info("Executing RML rules for {}...", file);
      String execId = super.createComplexCommand(rmlMapperJavaContainerId, "java", "-jar", "/rmlmapper.jar", "-m",
          Paths.get(TEMP_CONTAINER_DATA_DIR_PATH).resolve(file).toString(), "-s", "turtle")
          .withOutputStream(outputStream)
          .withErrorStream(errorStream)
          .exec();
      super.handleErrors(errorStream, execId, LOGGER);
    });
    super.deleteDirectory(rmlMapperJavaContainerId, TEMP_CONTAINER_DATA_DIR_PATH);
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
   * Generate RML rules from YARRRML files in the directory if available.
   * 
   * @param dirPath   Target directory path.
   * @param namespace Target namespace to upload the converted RDF triples.
   */
  private Map<String, byte[]> genRmlRules(Path dirPath, String namespace) {
    LOGGER.info("Retrieving the target SPARQL endpoint...");
    BlazegraphEndpointConfig blazegraphConfig = readEndpointConfig(EndpointNames.BLAZEGRAPH,
        BlazegraphEndpointConfig.class);
    String sparqlEndpoint = blazegraphConfig.getUrl(namespace);

    LOGGER.info("Converting the YARRRML inputs into RML rules...");
    String containerId = super.getContainerId(super.getContainerName());
    Collection<URI> ymlFiles = this.getFiles(dirPath, YML_FILE_EXTENSION);

    // Convert from URI to String and append them to command
    Map<String, byte[]> yarrrmlRules = ymlFiles.stream()
        .map(ymlFile -> {
          try {
            return new YarrrmlFile(FileUtils.appendDirectoryPath(ymlFile, TEMP_CONTAINER_DATA_DIR_PATH),
                sparqlEndpoint);
          } catch (IOException e) {
            LOGGER.error(containerId, e);
            return new YarrrmlFile();
          }
        })
        .collect(Collectors.toMap(
            YarrrmlFile::getFileName,
            yarrrmlFile -> {
              try {
                return yarrrmlFile.write();
              } catch (IOException e) {
                LOGGER.error(containerId, e);
                return new byte[0];
              }
            }));
    // Send the files to a new data directory
    super.sendFilesContent(containerId, yarrrmlRules, TEMP_CONTAINER_DATA_DIR_PATH);

    // Execute the command and return the RML rules alongside the TTL file name
    Map<String, byte[]> rmlRules = yarrrmlRules.entrySet().stream()
        .collect(Collectors.toMap(
            entry -> FileUtils.replaceExtension(entry.getKey(), TTL_FILE_EXTENSION),
            entry -> {
              LOGGER.info("Generating RML rules from {}...", entry.getKey());
              ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
              ByteArrayOutputStream errorStream = new ByteArrayOutputStream();
              String execId = super.createComplexCommand(containerId, YARRRML_PARSER_EXECUTABLE_PATH, "-i",
                  Paths.get(TEMP_CONTAINER_DATA_DIR_PATH).resolve(entry.getKey()).toString())
                  .withOutputStream(outputStream)
                  .withErrorStream(errorStream)
                  .exec();
              super.handleErrors(errorStream, execId, LOGGER);
              return outputStream.toByteArray();
            }));
    LOGGER.info("RML rules are generated. Removing any temporary YARRRML files in the container...");
    super.deleteDirectory(containerId, TEMP_CONTAINER_DATA_DIR_PATH);
    return rmlRules;
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
