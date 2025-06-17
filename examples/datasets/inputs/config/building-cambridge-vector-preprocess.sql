-- Extensions needed
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS postgis_sfcgal;

-- Create sequence for id, so no building and surface geometry will have the same id

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_sequences
        WHERE schemaname = 'public' AND sequencename = 'shared_id_seq'
    ) THEN
        CREATE SEQUENCE shared_id_seq START 1;
    END IF;
END $$;

-- Create the "building" table (if not exists)
CREATE TABLE IF NOT EXISTS building (
    id INT PRIMARY KEY DEFAULT nextval('shared_id_seq'),
    uuid UUID DEFAULT gen_random_uuid(),
    os_topo_toid TEXT,
    height DOUBLE PRECISION,
    footprint_id INT
);

-- Create the "surface_geometry" table (if not exists)
CREATE TABLE IF NOT EXISTS surface_geometry (
    id INT PRIMARY KEY DEFAULT nextval('shared_id_seq'),
    uuid UUID DEFAULT gen_random_uuid(),
    building_id INT NOT NULL,
    surface_type TEXT,
    geom GEOMETRY(MULTIPOLYGONZ)
);

-- Add foreign key constraints (if not exists)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_name = 'fk_building_footprint'
    ) THEN
        ALTER TABLE building
        ADD CONSTRAINT fk_building_footprint FOREIGN KEY (footprint_id)
        REFERENCES surface_geometry(id) ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_name = 'fk_surface_building'
    ) THEN
        ALTER TABLE surface_geometry
        ADD CONSTRAINT fk_surface_building FOREIGN KEY (building_id)
        REFERENCES building(id) ON DELETE CASCADE;
    END IF;
END $$;

-- Create spatial index for faster queries (if not exists)
CREATE INDEX IF NOT EXISTS idx_surface_geometry_geom ON surface_geometry USING GIST(geom);
CREATE INDEX IF NOT EXISTS idx_surface_geometry_building_id ON surface_geometry(building_id);

-- Augment original data to have a footprint with elevation
ALTER TABLE "building-cambridge" ADD COLUMN "footprint" GEOMETRY(MULTIPOLYGONZ);

UPDATE "building-cambridge"
SET "footprint" = ST_SetSRID(
    ST_Translate(ST_Force3D("polygon"), 0, 0, "abshmin"),
    ST_SRID("polygon")
);

-- Create index for geometries
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_indexes
        WHERE indexname = 'building-cambridge-footprint-index'
    ) THEN
        CREATE INDEX "building-cambridge-footprint-index"
        ON "building-cambridge" USING GIST ("footprint");
    END IF;
END $$;

-- Insert into building table
INSERT INTO building (os_topo_toid, height, footprint_id)
SELECT
    "os_topo_toid",
    COALESCE("absh2", "abshmax" - "abshmin", "relh2") AS height,
    NULL AS footprint_id
FROM "building-cambridge"
WHERE "os_topo_toid" IS NOT NULL
AND NOT EXISTS (
    SELECT 1 FROM building b
    WHERE b."os_topo_toid" = "building-cambridge"."os_topo_toid"
);

-- Add footprint to surface geometry table
INSERT INTO "surface_geometry" (building_id, surface_type, geom)
SELECT
    building.id AS building_id,
    'footprint' AS surface_type,
    "building-cambridge"."footprint" AS geom
FROM building
JOIN "building-cambridge" ON building."os_topo_toid" = "building-cambridge"."os_topo_toid";

-- Add footprint ID back to building table
UPDATE building b
SET footprint_id = s.id
FROM surface_geometry s
WHERE
    s.surface_type = 'footprint'
    AND s.building_id = b.id;

-- Extract building geometry using a CTE
WITH extruded AS (
    SELECT
        building_id,
        ST_Extrude(geom, 0.0, 0.0, height) AS geom,
        ST_SRID(geom) AS srid
    FROM "surface_geometry" JOIN "building" ON "surface_geometry"."building_id" = "building"."id"
    WHERE surface_type = 'footprint'
)
-- Populate `"surface_geometry"` using precomputed geometries
INSERT INTO "surface_geometry" (building_id, surface_type, geom)
SELECT
    building_id,
    'unknown',
    ST_SetSRID((ST_Dump(geom)).geom, srid)
FROM extruded;

-- Categorize surfaces based on height and elevation
UPDATE "surface_geometry" sg
SET surface_type = 
    CASE 
        WHEN ST_ZMin(sg.geom) = ST_ZMax(sg.geom) AND ST_ZMin(sg.geom) = (
            SELECT MIN(ST_ZMin(geom)) FROM "surface_geometry" WHERE building_id = sg.building_id
        ) THEN 'ground'
        WHEN ST_ZMin(sg.geom) = ST_ZMax(sg.geom) AND ST_ZMin(sg.geom) = (
            SELECT MAX(ST_ZMax(geom)) FROM "surface_geometry" WHERE building_id = sg.building_id
        ) THEN 'roof'
        ELSE 'wall'
    END
WHERE sg.surface_type = 'unknown';

-- Drop table of original data

DROP TABLE "building-cambridge"