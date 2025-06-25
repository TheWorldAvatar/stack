package com.cmclinnovations.stack.clients.core.datasets;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cmclinnovations.stack.clients.citydb.CityDBClient;
import com.cmclinnovations.stack.clients.gdal.GDALClient;
import com.cmclinnovations.stack.clients.gdal.Ogr2OgrOptions;
import com.cmclinnovations.stack.clients.utils.JsonHelper;
import com.fasterxml.jackson.annotation.JsonProperty;

public class XtoCityDB extends CityDB {

    private static final Logger logger = LoggerFactory.getLogger(XtoCityDB.class);

    @JsonProperty
    private Ogr2OgrOptions ogr2ogrOptions = new Ogr2OgrOptions();

    @JsonProperty
    private double minArea = 0.;
    @JsonProperty
    Map<String, String> columnMap = new HashMap<>(Map.of(
            "IDval", "os_topo_toid",
            "IDname", "os_topo_toid",
            "polygon", "polygon",
            "elevation", "abshmin",
            "height", "relh2"));
    @JsonProperty
    private String preprocessSql;

    @Override
    protected void loadDataInternal(Path dataSubsetDir, String database, String baseIRI, String lineage) {
        setPreviousFile(dataSubsetDir.resolveSibling(dataSubsetDir.getFileName() + "_previous")
                .resolve("previous.gz"));
        logger.info("Uploading original data to PostGIS...");
        GDALClient.getInstance()
                .uploadVectorFilesToPostGIS(database, getSchema(), getTable(), dataSubsetDir.toString(),
                        ogr2ogrOptions, false);
        logger.info("Initialising CityDB schema...");
        CityDBClient.getInstance()
                .updateDatabase(database, getSridIn());
        logger.info("Preparing original data...");
        CityDBClient.getInstance().preparePGforCityDB(database, getTable(), JsonHelper.handleFileValues(preprocessSql),
                minArea, columnMap);
        logger.info("Inserting processed data into CityDB schema...");
        CityDBClient.getInstance().populateCityDBbySQL(database, lineage, columnMap);
        CityDBClient.getInstance().addIRIs(database, baseIRI);
    }

    @Override
    public void createLayer(String database) {
        generateTiles(database);
    }

}
