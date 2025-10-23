package com.cmclinnovations.stack.clients.timeseries;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;

import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;

import com.cmclinnovations.stack.clients.core.EndpointNames;
import com.cmclinnovations.stack.clients.ontop.OntopClient;
import com.cmclinnovations.stack.clients.utils.LocalTempDir;
import com.cmclinnovations.stack.services.OntopService;
import com.cmclinnovations.stack.services.ServiceManager;
import com.cmclinnovations.stack.services.config.ServiceConfig;

import uk.ac.cam.cares.jps.base.exception.JPSRuntimeException;
import uk.ac.cam.cares.jps.base.query.RemoteStoreClient;
import uk.ac.cam.cares.jps.base.timeseries.TimeSeriesRDBClientOntop;

public class TimeSeriesRDBClient<T> extends TimeSeriesRDBClientOntop<T> {

    private static final String UNIX_TRS = "http://dbpedia.org/resource/Unix_time";
    private static final String GENERIC_TRS = "http://example.org/TRS_placeholder";

    /**
     * Logger for error output.
     */
    private static final Logger LOGGER = LogManager.getLogger(TimeSeriesRDBClient.class);

    // IRI of temporal reference system, used in ontop mapping to indicate reference
    // of time, e.g. Unix
    private String trsIri;

    // name of ontop container
    private String ontopName = "ontop-timeseries";

    public TimeSeriesRDBClient(Class<T> timeClass) {
        super(timeClass);
        if (Instant.class == timeClass) {
            LOGGER.info("Time class is Instant, TRS is set to {}", UNIX_TRS);
            trsIri = UNIX_TRS;
        } else {
            LOGGER.info("Time class is not Instant, TRS is set to {}", GENERIC_TRS);
            trsIri = GENERIC_TRS;
        }
    }

    public void setTrs(String trsIri) {
        LOGGER.info("TRS is set to {}", trsIri);
        this.trsIri = trsIri;
    }

    public void setOntopName(String ontopName) {
        this.ontopName = ontopName;
    }

    @Override
    public List<Integer> bulkInitTimeSeriesTable(List<List<String>> dataIRIs, List<List<Class<?>>> dataClasses,
            List<String> tsIRIs, Integer srid, Connection conn) {
        List<Integer> result = super.bulkInitTimeSeriesTable(dataIRIs, dataClasses, tsIRIs, srid, conn);

        // spin up a new ontop container if it does not exist
        configureOntop();

        return result;
    }

    private void configureOntop() {
        String stackName = System.getenv("STACK_NAME");
        if (stackName == null) {
            LOGGER.warn("STACK_NAME not detected, skipping Ontop intialisation");
            return;
        }

        ServiceManager serviceManager = new ServiceManager(false);

        ServiceConfig newOntopServiceConfig = serviceManager.duplicateServiceConfig(EndpointNames.ONTOP, ontopName);
        newOntopServiceConfig.setEnvironmentVariable(OntopService.ONTOP_DB_NAME, "postgres");

        newOntopServiceConfig.getEndpoints()
                .replaceAll((endpointName, connection) -> new com.cmclinnovations.stack.services.config.Connection(
                        connection.getUrl(),
                        connection.getUri(),
                        URI.create(connection.getExternalPath().toString()
                                .replace(EndpointNames.ONTOP, ontopName))));
        serviceManager.initialiseService(stackName, ontopName);

        OntopClient ontopClient = OntopClient.getInstance(ontopName);
        String ontopUrl = ontopClient.readEndpointConfig().getUrl();

        // check if mapping exists and only upload mapping if it does not exist
        RemoteStoreClient remoteStoreClient = new RemoteStoreClient(ontopUrl);
        String query = "SELECT * WHERE { ?x ?y ?z } LIMIT 1";

        JSONArray queryResult = null;

        // try to send a query to ontop, catch exceptions in case it is still
        // initialising
        int attempts = 0;
        int maxAttempts = 5;
        while (attempts < maxAttempts) {
            try {
                queryResult = remoteStoreClient.executeQuery(query);
                break;
            } catch (Exception e) {
                attempts++;
                try {
                    Thread.sleep(10_000); // wait 10 seconds before retrying
                } catch (Exception ie) {
                    throw new JPSRuntimeException("Interrupted while retrying query to ontop", e);
                }
            }
        }

        if (queryResult == null) {
            throw new JPSRuntimeException("Failed to execute query after " + maxAttempts + " attempts.");
        }

        if (queryResult.isEmpty()) {

            // create temporary file for ontop mapping
            try (LocalTempDir tempDir = new LocalTempDir()) {

                String obda = prepareMapping();

                Path filePath = tempDir.getPath().resolve("ontop.obda");
                Files.write(filePath, obda.getBytes());

                // sends obda to container
                ontopClient.updateOBDA(filePath);
            } catch (IOException e) {
                throw new JPSRuntimeException("Failed to write ontop mapping into temporary folder", e);
            }

        }
    }

    /**
     * set TRS in Ontop mapping
     * 
     * @return
     */
    private String prepareMapping() {
        // read template from resources folder
        try (InputStream is = TimeSeriesRDBClientOntop.class.getResourceAsStream("timeseries_ontop_template.obda")) {
            return IOUtils.toString(is, StandardCharsets.UTF_8)
                    .replace("[TRS_REPLACE]", trsIri);
        } catch (IOException e) {
            throw new JPSRuntimeException("Error while reading timeseries_ontop_template.obda", e);
        }
    }

}
