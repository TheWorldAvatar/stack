SET search_path TO public,citydb;
create table "public"."raw_building" AS
(SELECT "{TOID}", 'building_' || gen_random_uuid() AS "gmlid", box2envelope(Box3D(ST_Translate(ST_Extrude("{footprint}",0,0,"{height}"),0,0,"{elevation}"))) AS "envelope", ST_Translate(ST_Extrude("{footprint}",0,0,"{height}"),0,0,"{elevation}") AS "geom", "{height}" AS "mh"
FROM "public"."raw_data" WHERE ST_Area("{footprint}")>{minArea} AND "{height}" IS NOT NULL);
create table "public"."raw_surface" AS
(SELECT "building_gmlid", 'surface_' || gen_random_uuid() AS "gmlid", "class", "geom", box2envelope(Box3D("geom")) AS "envelope" FROM
(SELECT "building_gmlid", "geom",
    CASE WHEN ST_Zmin("geom")=ST_Zmax("geom") THEN
        CASE WHEN ST_Zmin("geom")="bzl" THEN 35 ELSE 33 END
    ELSE 34 END AS "class" FROM
(SELECT "gmlid" AS "building_gmlid", ST_Zmin("geom") AS "bzl", (ST_Dump(ST_CollectionExtract("geom"))).geom AS "geom"
FROM "public"."raw_building") AS "table1") AS "table2");
INSERT INTO "citydb"."cityobject" ("objectclass_id","gmlid","envelope","creation_date","last_modification_date","updating_person","lineage")
SELECT 26,"gmlid", "envelope", CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,user, '{lineage}'
FROM "public"."raw_building";
INSERT INTO "citydb"."building" ("id","objectclass_id","building_root_id","measured_height","measured_height_unit")
SELECT "citydb"."cityobject"."id",26,"citydb"."cityobject"."id","public"."raw_building"."mh", '#m'
FROM "citydb"."cityobject","public"."raw_building" WHERE "citydb"."cityobject"."gmlid" = "public"."raw_building"."gmlid";
INSERT INTO "citydb"."cityobject_genericattrib" ("attrname","datatype","strval","cityobject_id")
SELECT 'os_topo_toid',1,"public"."raw_building"."{TOID}","citydb"."cityobject"."id"
FROM "citydb"."cityobject","public"."raw_building" WHERE "citydb"."cityobject"."gmlid" = "public"."raw_building"."gmlid";
UPDATE "citydb"."cityobject_genericattrib"
SET "root_genattrib_id" = "id"
WHERE "root_genattrib_id" IS NULL;
WITH "co" AS (
INSERT INTO "citydb"."cityobject" ("objectclass_id","gmlid","envelope","creation_date","last_modification_date","updating_person")
(SELECT "class","gmlid", "envelope", CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,user
FROM "public"."raw_surface")
RETURNING "objectclass_id","gmlid","id" AS "coid"),
"sgp" AS (
INSERT INTO "citydb"."surface_geometry" ("gmlid","is_solid","is_composite","is_triangulated","is_xlink","is_reverse","geometry","cityobject_id")
(SELECT "co"."gmlid" || '_parent',0,0,0,0,0,NULL,"co"."coid"
FROM "co")
RETURNING "id" AS "sgpid", "cityobject_id" AS "coid")
INSERT INTO "citydb"."surface_geometry" ("gmlid","parent_id","root_id","is_solid","is_composite","is_triangulated","is_xlink","is_reverse","geometry","cityobject_id")
(SELECT "co"."gmlid" || '_child',"sgp"."sgpid","sgp"."sgpid",0,0,0,0,0,"public"."raw_surface"."geom","sgp"."coid"
FROM "sgp","public"."raw_surface","co" WHERE
"public"."raw_surface"."gmlid" = "co"."gmlid" AND "sgp"."coid" = "co"."coid");
INSERT INTO "citydb"."thematic_surface" ("id","building_id","objectclass_id","lod2_multi_surface_id")
(SELECT "SQ"."id","BQ"."coid" AS "building_id","SQ"."objectclass_id","SQ"."lod2_multi_surface_id" FROM
(SELECT "citydb"."cityobject"."id" AS "coid","citydb"."cityobject"."gmlid" AS "building_gmlid"
FROM "citydb"."cityobject" WHERE "citydb"."cityobject"."objectclass_id"='26') AS "BQ",
(SELECT "citydb"."cityobject"."id","citydb"."cityobject"."objectclass_id", "public"."raw_surface"."building_gmlid" AS "building_gmlid",
"citydb"."surface_geometry"."id" AS "lod2_multi_surface_id"
FROM "citydb"."cityobject","public"."raw_surface","citydb"."surface_geometry"
WHERE "citydb"."cityobject"."gmlid"="public"."raw_surface"."gmlid" AND "citydb"."surface_geometry"."cityobject_id" = "citydb"."cityobject"."id" AND "citydb"."surface_geometry"."parent_id" IS NULL) AS "SQ"
WHERE "BQ"."building_gmlid"="SQ"."building_gmlid");
UPDATE "citydb"."surface_geometry"
SET "root_id" = "id"
WHERE "root_id" IS NULL;