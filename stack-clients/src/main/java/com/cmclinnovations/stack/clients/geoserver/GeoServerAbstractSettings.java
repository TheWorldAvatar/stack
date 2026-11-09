package com.cmclinnovations.stack.clients.geoserver;

import java.util.Map;

import it.geosolutions.geoserver.rest.encoder.GSLayerEncoder;
import it.geosolutions.geoserver.rest.encoder.GSLayerEncoder21;

abstract class GeoServerAbstractSettings implements GeoServerDimensionSettings {

    private final GSLayerEncoder21 layerSettings = new GSLayerEncoder21();

    private Map<String, UpdatedGSFeatureDimensionInfoEncoder> dimensions;

    public GSLayerEncoder getLayerSettings() {
        return layerSettings;
    }

    public void setDefaultStyle(String defaultStyle) {
        layerSettings.setDefaultStyle(defaultStyle);
    }

    @Override
    public Map<String, UpdatedGSFeatureDimensionInfoEncoder> getDimensions() {
        return dimensions;
    }

    @Override
    public void setDimensions(Map<String, UpdatedGSFeatureDimensionInfoEncoder> dimensions) {
        this.dimensions = dimensions;
    }
}
