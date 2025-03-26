package com.cmclinnovations.stack.clients.core.datasets;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.rdf4j.model.vocabulary.DCAT;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.eclipse.rdf4j.sparqlbuilder.constraint.propertypath.builder.PropertyPathBuilder;
import org.eclipse.rdf4j.sparqlbuilder.core.SparqlBuilder;
import org.eclipse.rdf4j.sparqlbuilder.core.Variable;
import org.eclipse.rdf4j.sparqlbuilder.core.query.Queries;
import org.eclipse.rdf4j.sparqlbuilder.core.query.SelectQuery;
import org.eclipse.rdf4j.sparqlbuilder.rdf.Iri;
import org.json.JSONArray;

import com.cmclinnovations.stack.clients.blazegraph.BlazegraphClient;
import com.cmclinnovations.stack.clients.core.EndpointNames;
import com.cmclinnovations.stack.clients.core.StackClient;
import com.cmclinnovations.stack.clients.geoserver.GeoServerClient;
import com.cmclinnovations.stack.clients.geoserver.StaticGeoServerData;
import com.cmclinnovations.stack.clients.ontop.OntopClient;
import com.cmclinnovations.stack.clients.postgis.PostGISClient;
import com.cmclinnovations.stack.clients.rdf4j.Rdf4jClient;
import com.cmclinnovations.stack.clients.utils.JsonHelper;
import com.cmclinnovations.stack.services.OntopService;
import com.cmclinnovations.stack.services.ServiceManager;
import com.cmclinnovations.stack.services.config.Connection;
import com.cmclinnovations.stack.services.config.ServiceConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import uk.ac.cam.cares.jps.base.derivation.ValuesPattern;
import uk.ac.cam.cares.jps.base.query.RemoteStoreClient;

public class DatasetLoader {

    private static final ServiceManager serviceManager = new ServiceManager(false);

    private static final ObjectMapper objectMapper = JsonHelper.getMapper();

    private final String catalogNamespace;

    public DatasetLoader(String catalogNamespace) {
        this.catalogNamespace = catalogNamespace;
    }

    public DatasetLoader() {
        this("kb");
    }

    public void loadInputDatasets(Path configPath, String selectedDatasetName) {

        List<Dataset> allDatasets = DatasetReader.getAllDatasets(configPath);

        Stream<Dataset> selectedDatasets = DatasetReader.getStackSpecificDatasets(allDatasets, selectedDatasetName);

        loadDatasets(selectedDatasets);

    }

    public void loadDatasets(Collection<Dataset> selectedDatasets) {
        selectedDatasets.forEach(this::loadDataset);
    }

    public void loadDatasets(Stream<Dataset> selectedDatasets) {
        selectedDatasets.forEach(this::loadDataset);
    }

    public void loadDataset(Dataset dataset) {
        Path directory = dataset.getDirectory();

        if (!dataset.isSkip()) {
            List<DataSubset> dataSubsets = dataset.getDataSubsets();
            // Ensure PostGIS database exists, if specified
            configurePostgres(dataset, dataSubsets);

            List<String> ontologyDatasetNames = dataset.getOntologyDatasetNames();

            // Ensure Blazegraph namespace exists, if specified
            configureBlazegraph(dataset, ontologyDatasetNames);

            configureGeoServer(dataset, directory);

            dataSubsets.forEach(subset -> subset.load(dataset));

            configureOntop(dataset, directory, ontologyDatasetNames);

            // record added datasets in the default kb namespace
            BlazegraphClient.getInstance().getRemoteStoreClient(catalogNamespace)
                    .executeUpdate(new DCATUpdateQuery().getUpdateQuery(dataset));

            runRules(dataset, directory);

            createSparqlEndpointRdf4jRepos(dataset.getName());
        }
    }

    private class ServiceDescription {
        private String id;
        private String title;
        private String url;
        private Iri type;

        public String getId() {
            return id;
        }

        public String getTitle() {
            return title;
        }

        public String getUrl() {
            return url;
        }

        public Iri getType() {
            return type;
        }

    }

    private void createSparqlEndpointRdf4jRepos(String datasetName) {
        List<ServiceDescription> serviceDescriptions = getServiceDescriptions(datasetName);

        Rdf4jClient rdf4jClient = Rdf4jClient.getInstance();

        serviceDescriptions.stream().filter(sd -> sd.getType().equals(SparqlConstants.BLAZEGRAPH_SERVICE)).forEach(
                sd -> rdf4jClient.createSparqlRepository(sd.getId(), sd.getTitle() + " (Blazegraph)", sd.getUrl()));

        serviceDescriptions.stream().filter(sd -> sd.getType().equals(SparqlConstants.ONTOP_SERVICE)).forEach(
                sd -> rdf4jClient.createSparqlRepository(sd.getId(), sd.getTitle() + " (Ontop)", sd.getUrl()));

        ServiceDescription rdf4jSD = serviceDescriptions.stream()
                .filter(sd -> sd.getType().equals(SparqlConstants.RDF4J_SERVICE)).findAny().orElse(null);

        if (null != rdf4jSD) {
            List<ServiceDescription> federatingSDs = serviceDescriptions.stream()
                    .filter(sd -> sd.getType().equals(SparqlConstants.BLAZEGRAPH_SERVICE)
                            && sd.getType().equals(SparqlConstants.ONTOP_SERVICE))
                    .collect(Collectors.toList());
            if (federatingSDs.size() == 1) {
                rdf4jClient.createSparqlRepository(rdf4jSD.getId(), rdf4jSD.getTitle(), federatingSDs.get(0).getUrl());
            } else if (federatingSDs.size() > 1) {
                List<String> ids = federatingSDs.stream().map(ServiceDescription::getId).collect(Collectors.toList());
                rdf4jClient.createFederatedRepository(rdf4jSD.getId(), rdf4jSD.getTitle(), ids);
            }
        }
    }

    private List<ServiceDescription> getServiceDescriptions(String datasetName) {
        Variable idVar = SparqlBuilder.var("id");
        Variable titleVar = SparqlBuilder.var("title");
        Variable urlVar = SparqlBuilder.var("url");
        Variable typeVar = SparqlBuilder.var("type");

        Variable serviceVar = SparqlBuilder.var("service");

        SelectQuery query = Queries.SELECT(idVar, titleVar, urlVar, typeVar)
                .where(
                        serviceVar.isA(typeVar)
                                .andHas(PropertyPathBuilder.of(DCAT.SERVES_DATASET).then(DCTERMS.TITLE).build(),
                                        datasetName)
                                .andHas(DCTERMS.IDENTIFIER, idVar)
                                .andHas(DCTERMS.TITLE, titleVar)
                                .andHas(DCAT.ENDPOINT_URL, urlVar));

        JSONArray queryResult = BlazegraphClient.getInstance().getRemoteStoreClient(catalogNamespace)
                .executeQuery(query.getQueryString());

        try {
            return objectMapper.readValue(queryResult.toString(),
                    new TypeReference<List<ServiceDescription>>() {
                    });
        } catch (IOException ex) {
            throw new RuntimeException(
                    "Error occurred while reading result of finding the service descriptions for the dataset '"
                            + datasetName + "''.",
                    ex);
        }
    }

    private void configurePostgres(Dataset dataset, List<DataSubset> dataSubsets) {
        if (dataset.usesPostGIS()) {
            PostGISClient postGISClient = PostGISClient.getInstance();
            postGISClient.createDatabase(dataset.getDatabase());
            dataSubsets.stream().filter(DataSubset::usesPostGIS)
                    .filter(PostgresDataSubset.class::isInstance)
                    .forEach(subset -> postGISClient.createSchema(dataset.getDatabase(),
                            ((PostgresDataSubset) subset).getSchema()));
        }
    }

    private void configureBlazegraph(Dataset dataset, List<String> ontologyDatasetNames) {
        if (dataset.usesBlazegraph()) {
            BlazegraphClient blazegraphClient = BlazegraphClient.getInstance();
            blazegraphClient.createNamespace(dataset.getNamespace(),
                    dataset.getNamespaceProperties());

            if (!ontologyDatasetNames.isEmpty()) {
                blazegraphClient.cloneDatasets(dataset.getNamespace(), ontologyDatasetNames, catalogNamespace);
            }
        }
    }

    private void runRules(Dataset dataset, Path directory) {
        if (dataset.usesBlazegraph()) {
            BlazegraphClient blazegraphClient = BlazegraphClient.getInstance();
            RemoteStoreClient remoteStoreClient = blazegraphClient.getRemoteStoreClient(dataset.getNamespace());

            blazegraphClient.runRules(remoteStoreClient,
                    dataset.getRules().stream().map(directory::resolve).collect(Collectors.toList()));
        }
    }

    private void configureGeoServer(Dataset dataset, Path directory) {
        if (dataset.usesGeoServer()) {
            GeoServerClient geoServerClient = GeoServerClient.getInstance();
            String workspaceName = dataset.getWorkspaceName();
            // Ensure GeoServer workspace exists
            geoServerClient.createWorkspace(workspaceName);
            // Upload styles to GeoServer
            dataset.getGeoserverStyles().forEach(style -> geoServerClient.loadStyle(style, workspaceName));
        }

        if (dataset.hasStaticGeoServerData()) {
            GeoServerClient geoServerClient = GeoServerClient.getInstance();
            StaticGeoServerData staticGeoServerData = dataset.getStaticGeoServerData();
            geoServerClient.loadIcons(directory, staticGeoServerData.getIconsDir());
            geoServerClient.loadOtherFiles(directory, staticGeoServerData.getOtherFiles());
        }
    }

    private void configureOntop(Dataset dataset, Path directory, List<String> ontologyDatasetNames) {
        if (dataset.usesOntop()) {
            String newOntopServiceName = dataset.getOntopName();

            ServiceConfig newOntopServiceConfig = serviceManager.duplicateServiceConfig(EndpointNames.ONTOP,
                    newOntopServiceName);

            newOntopServiceConfig.setEnvironmentVariable(OntopService.ONTOP_DB_NAME, dataset.getDatabase());
            newOntopServiceConfig.getEndpoints()
                    .replaceAll((endpointName, connection) -> new Connection(
                            connection.getUrl(),
                            connection.getUri(),
                            URI.create(connection.getExternalPath().toString()
                                    .replace(EndpointNames.ONTOP, newOntopServiceName))));

            serviceManager.initialiseService(StackClient.getStackName(), newOntopServiceName);

            List<String> ontopMappings = dataset.getOntopMappings();

            OntopClient ontopClient = OntopClient.getInstance(newOntopServiceName);
            ontopMappings.forEach(mapping -> ontopClient.updateOBDA(directory.resolve(mapping)));

            if (PostGISClient.DEFAULT_DATABASE_NAME.equals(dataset.getDatabase())) {
                OntopClient defaultOntopClient = OntopClient.getInstance();
                ontopMappings.forEach(mapping -> defaultOntopClient.updateOBDA(directory.resolve(mapping)));
            }

            ontopClient.uploadOntology(catalogNamespace, ontologyDatasetNames);

            ontopClient.uploadRules(dataset.getRules().stream().map(directory::resolve).collect(Collectors.toList()));
        }
    }
}
