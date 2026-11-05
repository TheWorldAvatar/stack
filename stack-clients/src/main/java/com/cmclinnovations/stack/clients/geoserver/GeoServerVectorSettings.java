package com.cmclinnovations.stack.clients.geoserver;

public class GeoServerVectorSettings extends GeoServerAbstractSettings {

    private UpdatedGSVirtualTableEncoder virtualTable;
    private UpdatedGSFeatureTypeEncoder featureTypeSettings = new UpdatedGSFeatureTypeEncoder();

    public UpdatedGSVirtualTableEncoder getVirtualTable() {
        return virtualTable;
    }

    public void setVirtualTable(UpdatedGSVirtualTableEncoder virtualTable) {
        this.virtualTable = virtualTable;
    }

    public UpdatedGSFeatureTypeEncoder getFeatureTypeSettings() {
        return featureTypeSettings;
    }
}
