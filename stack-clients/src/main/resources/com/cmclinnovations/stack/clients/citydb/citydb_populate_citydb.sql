SET
    search_path TO public,
    citydb;

DROP TABLE IF EXISTS "public"."building_without_footprint_CityDB",
"public"."precise_footprint_CityDB",
"public"."footprint_soup_CityDB",
"public"."fp_parent";

INSERT INTO
    "citydb"."cityobject" (
        "objectclass_id",
        "gmlid",
        "envelope",
        "creation_date",
        "last_modification_date",
        "updating_person",
        "lineage"
    )
SELECT
    26,
    "gmlid",
    box2envelope(Box3D("geom")),
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    user,
    '{lineage}'
FROM
    "public"."raw_building_XtoCityDB";

INSERT INTO
    "citydb"."building" (
        "id",
        "objectclass_id",
        "building_root_id",
        "measured_height",
        "measured_height_unit"
    )
SELECT
    "citydb"."cityobject"."id",
    26,
    "citydb"."cityobject"."id",
    "public"."raw_building_XtoCityDB"."mh",
    '#m'
FROM
    "citydb"."cityobject",
    "public"."raw_building_XtoCityDB"
WHERE
    "citydb"."cityobject"."gmlid" = "public"."raw_building_XtoCityDB"."gmlid";

INSERT INTO
    "citydb"."cityobject_genericattrib" (
        "attrname",
        "datatype",
        "strval",
        "cityobject_id"
    )
SELECT
    '{IDname}',
    1,
    "public"."raw_building_XtoCityDB"."{IDval}",
    "citydb"."cityobject"."id"
FROM
    "citydb"."cityobject",
    "public"."raw_building_XtoCityDB"
WHERE
    "citydb"."cityobject"."gmlid" = "public"."raw_building_XtoCityDB"."gmlid";

UPDATE
    "citydb"."cityobject_genericattrib"
SET
    "root_genattrib_id" = "id"
WHERE
    "root_genattrib_id" IS NULL;

WITH "co" AS (
    INSERT INTO
        "citydb"."cityobject" (
            "objectclass_id",
            "gmlid",
            "envelope",
            "creation_date",
            "last_modification_date",
            "updating_person"
        ) (
            SELECT
                "class",
                "gmlid",
                box2envelope(Box3D("geom")),
                CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP,
                user
            FROM
                "public"."raw_surface_XtoCityDB"
        ) RETURNING "objectclass_id",
        "gmlid",
        "id" AS "coid"
),
"sgp" AS (
    INSERT INTO
        "citydb"."surface_geometry" (
            "gmlid",
            "is_solid",
            "is_composite",
            "is_triangulated",
            "is_xlink",
            "is_reverse",
            "geometry",
            "cityobject_id"
        ) (
            SELECT
                "co"."gmlid" || '_parent',
                0,
                0,
                0,
                0,
                0,
                NULL,
                "co"."coid"
            FROM
                "co"
        ) RETURNING "id" AS "sgpid",
        "cityobject_id" AS "coid"
)
INSERT INTO
    "citydb"."surface_geometry" (
        "gmlid",
        "parent_id",
        "root_id",
        "is_solid",
        "is_composite",
        "is_triangulated",
        "is_xlink",
        "is_reverse",
        "geometry",
        "cityobject_id"
    ) (
        SELECT
            "co"."gmlid" || '_child',
            "sgp"."sgpid",
            "sgp"."sgpid",
            0,
            0,
            0,
            0,
            0,
            "public"."raw_surface_XtoCityDB"."geom",
            "sgp"."coid"
        FROM
            "sgp",
            "public"."raw_surface_XtoCityDB",
            "co"
        WHERE
            "public"."raw_surface_XtoCityDB"."gmlid" = "co"."gmlid"
            AND "sgp"."coid" = "co"."coid"
    );

INSERT INTO
    "citydb"."thematic_surface" (
        "id",
        "building_id",
        "objectclass_id",
        "lod2_multi_surface_id"
    ) (
        SELECT
            "SQ"."id",
            "BQ"."coid" AS "building_id",
            "SQ"."objectclass_id",
            "SQ"."lod2_multi_surface_id"
        FROM
            (
                SELECT
                    "citydb"."cityobject"."id" AS "coid",
                    "citydb"."cityobject"."gmlid" AS "building_gmlid"
                FROM
                    "citydb"."cityobject"
                WHERE
                    "citydb"."cityobject"."objectclass_id" = '26'
            ) AS "BQ",
            (
                SELECT
                    "citydb"."cityobject"."id",
                    "citydb"."cityobject"."objectclass_id",
                    "public"."raw_surface_XtoCityDB"."building_gmlid" AS "building_gmlid",
                    "citydb"."surface_geometry"."id" AS "lod2_multi_surface_id"
                FROM
                    "citydb"."cityobject",
                    "public"."raw_surface_XtoCityDB",
                    "citydb"."surface_geometry"
                WHERE
                    "citydb"."cityobject"."gmlid" = "public"."raw_surface_XtoCityDB"."gmlid"
                    AND "citydb"."surface_geometry"."cityobject_id" = "citydb"."cityobject"."id"
                    AND "citydb"."surface_geometry"."parent_id" IS NULL
            ) AS "SQ"
        WHERE
            "BQ"."building_gmlid" = "SQ"."building_gmlid"
    );

UPDATE
    "citydb"."surface_geometry"
SET
    "root_id" = "id"
WHERE
    "root_id" IS NULL;

-- create footprint
CREATE TABLE public."building_without_footprint_CityDB" AS (
    SELECT
        c.id,
        c.gmlid,
        ST_Zmin(c.envelope) AS zmin
    FROM
        cityobject AS c
        JOIN "public"."raw_building_XtoCityDB" AS r ON c.gmlid = r.gmlid
);

CREATE TABLE public."precise_footprint_CityDB" AS (
    SELECT
        b.id AS building_id,
        ST_Union(
            ST_MakeValid(ST_Force2D(s.geom)),
            0.00000001
        ) AS U
    FROM
        "public"."raw_surface_XtoCityDB" AS s
        JOIN "public"."building_without_footprint_CityDB" AS b ON b.gmlid = s.building_gmlid
    WHERE
        s.class = 35
    GROUP BY
        building_id
);

CREATE TABLE public."footprint_soup_CityDB" AS (
    SELECT
        b.id,
        b.gmlid,
        ST_Force3D(
            (
                ST_Dump(p.U)
            ).geom,
            b.zmin
        ) AS geometry
    FROM
        "public"."building_without_footprint_CityDB" AS b
        LEFT JOIN public."precise_footprint_CityDB" AS p ON b.id = p.building_id
);

WITH temp AS (
    INSERT INTO
        citydb.surface_geometry (
            gmlid,
            is_solid,
            is_composite,
            is_triangulated,
            is_xlink,
            is_reverse,
            cityobject_id
        ) (
            SELECT
                b.gmlid || '_footprint',
                0,
                0,
                0,
                0,
                0,
                b.id
            FROM
                "public"."building_without_footprint_CityDB" AS b
        ) RETURNING id AS footprint_id,
        cityobject_id AS bid,
        gmlid
)
SELECT
    footprint_id,
    bid,
    gmlid INTO TABLE public.fp_parent
FROM
    temp;

UPDATE
    building AS b
SET
    lod0_footprint_id = f.footprint_id
FROM
    fp_parent AS f
WHERE
    b.id = f.bid;

INSERT INTO
    citydb.surface_geometry (
        gmlid,
        parent_id,
        root_id,
        is_solid,
        is_composite,
        is_triangulated,
        is_xlink,
        is_reverse,
        geometry,
        cityobject_id
    ) (
        SELECT
            s.gmlid || '_footprint_child',
            f.footprint_id,
            f.footprint_id,
            0,
            0,
            0,
            0,
            0,
            s.geometry,
            s.id
        FROM
            public."footprint_soup_CityDB" AS s
            JOIN public.fp_parent AS f ON s.id = f.bid
        WHERE
            GeometryType(geometry) = 'POLYGON'
    );

UPDATE
    surface_geometry
SET
    root_id = id
WHERE
    root_id IS NULL;

DROP TABLE IF EXISTS "public"."raw_building_XtoCityDB",
"public"."raw_surface_XtoCityDB",
"public"."building_without_footprint_CityDB",
"public"."precise_footprint_CityDB",
"public"."footprint_soup_CityDB",
"public"."fp_parent";