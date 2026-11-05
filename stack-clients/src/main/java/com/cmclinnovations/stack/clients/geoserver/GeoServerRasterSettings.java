package com.cmclinnovations.stack.clients.geoserver;

import it.geosolutions.geoserver.rest.encoder.coverage.GSImageMosaicEncoder;

public class GeoServerRasterSettings extends GeoServerAbstractSettings {

    private final GSImageMosaicEncoder dataStoreSettings = new GSImageMosaicEncoder();

    public GSImageMosaicEncoder getDataStoreSettings() {
        return dataStoreSettings;
    }
}
