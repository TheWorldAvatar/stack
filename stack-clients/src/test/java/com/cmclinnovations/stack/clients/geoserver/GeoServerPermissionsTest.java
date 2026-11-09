package com.cmclinnovations.stack.clients.geoserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GeoServerPermissionsTest {

    @TempDir
    Path tempDir;

    @Test
    void makesMosaicAndParentsGroupWritableWithoutWorldWrite() throws IOException {
        Path root = Files.createDirectory(tempDir.resolve("geotiffs"));
        Path mosaic = Files.createDirectories(root.resolve("database/schema/layer"));
        Path nested = Files.createDirectory(mosaic.resolve("nested"));
        Path raster = Files.writeString(nested.resolve("raster.tif"), "raster");
        Path properties = Files.writeString(mosaic.resolve("datastore.properties"), "properties");
        Path sibling = Files.createDirectory(root.resolve("other-layer"));

        for (Path directory : new Path[] { root, mosaic.getParent().getParent(), mosaic.getParent(),
                mosaic, nested, sibling }) {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        Files.setPosixFilePermissions(raster, PosixFilePermissions.fromString("rw-------"));
        Files.setPosixFilePermissions(properties, PosixFilePermissions.fromString("rw-r--r--"));
        var owner = Files.getOwner(raster);
        var group = Files.readAttributes(root, java.nio.file.attribute.PosixFileAttributes.class).group();

        GeoServerClient.prepareGeoTiffDirectory(root, mosaic);
        GeoServerClient.prepareGeoTiffDirectory(root, mosaic);

        for (Path directory : new Path[] { mosaic.getParent().getParent(), mosaic.getParent(), mosaic, nested }) {
            assertEquals("rwxrwxr-x", PosixFilePermissions.toString(Files.getPosixFilePermissions(directory)));
            assertEquals(group, Files.readAttributes(directory, java.nio.file.attribute.PosixFileAttributes.class).group());
        }
        assertEquals("rw-rw----", PosixFilePermissions.toString(Files.getPosixFilePermissions(raster)));
        assertEquals("rw-rw-r--", PosixFilePermissions.toString(Files.getPosixFilePermissions(properties)));
        assertEquals(owner, Files.getOwner(raster));
        assertEquals(group, Files.readAttributes(raster, java.nio.file.attribute.PosixFileAttributes.class).group());
        assertEquals("rwxr-xr-x", PosixFilePermissions.toString(Files.getPosixFilePermissions(root)));
        assertEquals("rwxr-xr-x", PosixFilePermissions.toString(Files.getPosixFilePermissions(sibling)));
    }

    @Test
    void doesNotChangeSymlinkTargetsOutsideMosaic() throws IOException {
        Path root = Files.createDirectory(tempDir.resolve("geotiffs"));
        Path mosaic = Files.createDirectory(root.resolve("layer"));
        Path outside = Files.writeString(tempDir.resolve("outside"), "unchanged");
        Files.setPosixFilePermissions(outside, PosixFilePermissions.fromString("rw-------"));
        Files.createSymbolicLink(mosaic.resolve("link"), outside);

        GeoServerClient.prepareGeoTiffDirectory(root, mosaic);

        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(outside)));
    }

    @Test
    void rejectsDirectoriesOutsideVolumeIncludingSymlinks() throws IOException {
        Path root = Files.createDirectory(tempDir.resolve("geotiffs"));
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Path link = Files.createSymbolicLink(root.resolve("link"), outside);

        assertThrows(IllegalArgumentException.class, () -> GeoServerClient.prepareGeoTiffDirectory(root, outside));
        assertThrows(IllegalArgumentException.class, () -> GeoServerClient.prepareGeoTiffDirectory(root, link));
        assertThrows(IllegalArgumentException.class, () -> GeoServerClient.prepareGeoTiffDirectory(root, root));
    }

    @Test
    void reportsMissingDirectory() throws IOException {
        Path root = Files.createDirectory(tempDir.resolve("geotiffs"));

        assertThrows(IOException.class, () -> GeoServerClient.prepareGeoTiffDirectory(root, root.resolve("missing")));
    }
}
