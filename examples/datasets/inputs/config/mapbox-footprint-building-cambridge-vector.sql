SELECT
    "public"."building"."id" AS "building_id",
    COALESCE("public"."building"."height", 100.0) AS "building_height",
    "public"."surface_geometry"."geom" AS "geometry",
    "public"."building"."os_topo_toid" AS "os_topo_toid",
    'https://theworldavatar.io/kg/Building/' || "public"."building"."uuid" AS "iri"
FROM
    "public"."building"
    JOIN "public"."surface_geometry" ON "public"."building"."id" = "public"."surface_geometry"."building_id"
WHERE
    "public"."surface_geometry"."surface_type" = 'footprint'