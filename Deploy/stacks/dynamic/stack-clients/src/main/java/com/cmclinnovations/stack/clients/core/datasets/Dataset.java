package com.cmclinnovations.stack.clients.core.datasets;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import com.cmclinnovations.stack.clients.geoserver.GeoServerClient;
import com.cmclinnovations.stack.clients.geoserver.GeoServerStyle;
import com.cmclinnovations.stack.clients.ontop.OntopClient;
import com.cmclinnovations.stack.clients.postgis.PostGISClient;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class Dataset {

    private String name;
    private Path datasetDirectory;
    private String workspaceName;

    private String database;

    private List<String> externalDatasets;

    private List<DataSubset> dataSubsets;

    private List<GeoServerStyle> geoserverStyles;

    private List<String> ontopMappings;

    private boolean skip;

    @JsonCreator
    public Dataset(@JsonProperty(value = "name") String name,
            @JsonProperty(value = "datasetDirectory") Path datasetDirectory,
            @JsonProperty(value = "workspace") String workspaceName,
            @JsonProperty(value = "database") String database,
            @JsonProperty(value = "externalDatasets") List<String> externalDatasets,
            @JsonProperty(value = "dataSubsets") List<DataSubset> dataSubsets,
            @JsonProperty(value = "styles") List<GeoServerStyle> geoserverStyles,
            @JsonProperty(value = "mappings") List<String> ontopMappings,
            @JsonProperty(value = "skip") boolean skip) {
        this.name = name;
        this.datasetDirectory = (null != datasetDirectory) ? datasetDirectory : Path.of(name);
        this.workspaceName = (null != workspaceName) ? workspaceName : name;
        this.database = database;
        this.externalDatasets = (null != externalDatasets) ? externalDatasets : new ArrayList<>();
        this.dataSubsets = (null != dataSubsets) ? dataSubsets : new ArrayList<>();
        this.geoserverStyles = (null != geoserverStyles) ? geoserverStyles : new ArrayList<>();
        this.ontopMappings = (null != ontopMappings) ? ontopMappings : new ArrayList<>();
        this.skip = skip;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<String> getExternalDatasets() {
        return externalDatasets;
    }

    public boolean isSkip() {
        return skip;
    }

    public void loadData() {
        if (!skip) {

            Path fullDatasetDir = Path.of("/inputs", "data").resolve(datasetDirectory);
            String fullDatasetDirStr = fullDatasetDir.toString();

            if (null != database) {
                PostGISClient.getInstance().createDatabase(database);
            }

            if (dataSubsets.stream().filter(Predicate.not(DataSubset::getSkip)).count() > 0
                    || !geoserverStyles.isEmpty()) {
                GeoServerClient geoServerClient = GeoServerClient.getInstance();
                geoServerClient.createWorkspace(workspaceName);

                geoserverStyles.forEach(style -> geoServerClient.loadStyle(style, workspaceName));
            }

            dataSubsets.stream().filter(Predicate.not(DataSubset::getSkip)).forEach(
                    subset -> {
                        subset.loadData(fullDatasetDirStr, database);
                        subset.runSQLPostProcess(database);
                        subset.createLayer(fullDatasetDirStr, workspaceName, database);
                    });

            ontopMappings.forEach(mapping -> OntopClient.getInstance().updateOBDA(fullDatasetDir.resolve(mapping)));
        }
    }

}
