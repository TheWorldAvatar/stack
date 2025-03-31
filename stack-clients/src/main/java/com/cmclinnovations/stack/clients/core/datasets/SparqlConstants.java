package com.cmclinnovations.stack.clients.core.datasets;

import org.eclipse.rdf4j.sparqlbuilder.rdf.Iri;
import org.eclipse.rdf4j.sparqlbuilder.rdf.Rdf;

public class SparqlConstants {
    private SparqlConstants() {
    }

    public static final String DEFAULT_BASE_IRI = "https://theworldavatar.io/kg/";
    public static final String BLAZEGRAPH_SERVICE_STRING = DEFAULT_BASE_IRI + "service#Blazegraph";
    public static final String POSTGIS_SERVICE_STRING = DEFAULT_BASE_IRI + "service#PostGIS";
    public static final String GEOSERVER_SERVICE_STRING = DEFAULT_BASE_IRI + "service#GeoServer";
    public static final String ONTOP_SERVICE_STRING = DEFAULT_BASE_IRI + "service#Ontop";
    public static final String RDF4J_SERVICE_STRING = DEFAULT_BASE_IRI + "service#RDF4J";
    public static final Iri BLAZEGRAPH_SERVICE = Rdf.iri(BLAZEGRAPH_SERVICE_STRING);
    public static final Iri POSTGIS_SERVICE = Rdf.iri(POSTGIS_SERVICE_STRING);
    public static final Iri GEOSERVER_SERVICE = Rdf.iri(GEOSERVER_SERVICE_STRING);
    public static final Iri ONTOP_SERVICE = Rdf.iri(ONTOP_SERVICE_STRING);
    public static final Iri RDF4J_SERVICE = Rdf.iri(RDF4J_SERVICE_STRING);
}