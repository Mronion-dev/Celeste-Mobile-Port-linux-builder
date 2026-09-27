package com.unlim8ted.celeste;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Exercises the actual importer with tiny fixtures, without launching Android. */
public final class GameFilesProbe {
    static final String DLL = "celeste/Celeste.dll", DATA = "_framework/data/data.data";
    static byte[] zip(Map<String, String> files) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> e : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey())); zip.write(e.getValue().getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
    static String fingerprint(String value) throws Exception {
        StringBuilder text = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))) text.append(String.format("%02x", b & 255));
        return text.toString();
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("celeste-import-test-");
        try {
            String manifest = fingerprint("code") + "\t4\t" + DLL + "\n" + fingerprint("data") + "\t4\t" + DATA + "\n";
            GameFiles importer = new GameFiles(root.toFile(), new ByteArrayInputStream(manifest.getBytes(StandardCharsets.UTF_8)));
            if (importer.isReady()) throw new AssertionError("Empty import was accepted");
            importer.install(new ByteArrayInputStream(zip(Map.of(DLL, "code", DATA, "data"))));
            if (!importer.isReady()) throw new AssertionError("Valid import rejected");
            for (Map<String, String> invalid : List.of(Map.of(DLL, "evil", DATA, "data"), Map.of(DLL, "code"),
                    Map.of(DLL, "too-large", DATA, "data"), Map.of(DLL, "code", DATA, "data", "../escape", "bad"))) {
                try { importer.install(new ByteArrayInputStream(zip(invalid))); throw new AssertionError("Invalid archive accepted"); }
                catch (IOException expected) { }
                if (!importer.isReady() || !Files.readString(root.resolve("owned-game/" + DLL)).equals("code"))
                    throw new AssertionError("Failed import damaged existing installation");
            }
            String newManifest = manifest.replace(fingerprint("code"), fingerprint("next"));
            if (new GameFiles(root.toFile(), new ByteArrayInputStream(newManifest.getBytes(StandardCharsets.UTF_8))).isReady())
                throw new AssertionError("Changed runtime did not request reimport");
            System.out.println("PASS: valid import, fingerprints, size limits, traversal, incomplete archive, rollback, update invalidation");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
            }
        }
    }
}
