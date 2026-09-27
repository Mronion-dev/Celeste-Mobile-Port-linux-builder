package com.unlim8ted.celeste;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Imports only the exact runtime payload described by the APK's trusted manifest. */
final class GameFiles {
    private final File root;
    private final LinkedHashMap<String, Entry> expected = new LinkedHashMap<>();
    private final String manifestHash;
    private static final class Entry {
        final long size; final String hash;
        Entry(long size, String hash) { this.size = size; this.hash = hash; }
    }
    GameFiles(File files, InputStream manifest) throws Exception {
        root = new File(files, "owned-game");
        byte[] bytes;
        try (InputStream in = manifest; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            bytes = out.toByteArray();
        }
        manifestHash = hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        for (String line : new String(bytes, StandardCharsets.UTF_8).split("\n")) {
            if (line.trim().isEmpty()) continue;
            String[] parts = line.trim().split("\t");
            if (parts.length != 3 || !parts[0].matches("[a-f0-9]{64}") ||
                !(parts[2].startsWith("celeste/") || parts[2].equals("_framework/data/data.data")) ||
                parts[2].contains("..") || parts[2].contains("\\")) throw new IOException("Invalid game manifest");
            long size = Long.parseLong(parts[1]);
            if (size < 1 || expected.put(parts[2], new Entry(size, parts[0])) != null)
                throw new IOException("Invalid game manifest entry");
        }
        if (!expected.containsKey("celeste/Celeste.dll") || !expected.containsKey("_framework/data/data.data"))
            throw new IOException("Incomplete game manifest");
        File old = new File(files, "owned-game-previous");
        if (!root.exists() && old.exists() && !old.renameTo(root)) throw new IOException("Cannot recover previous import");
    }
    boolean isReady() {
        try (BufferedReader reader = new BufferedReader(new FileReader(new File(root, "verified")))) {
            if (!manifestHash.equals(reader.readLine())) return false;
            for (Map.Entry<String, Entry> e : expected.entrySet())
                if (new File(root, e.getKey()).length() != e.getValue().size) return false;
            return true;
        } catch (IOException e) { return false; }
    }
    void install(InputStream archive) throws Exception {
        File stage = new File(root.getParentFile(), "owned-game-import");
        File old = new File(root.getParentFile(), "owned-game-previous");
        // Recover a process interruption between the two renames.
        if (!root.exists() && old.exists() && !old.renameTo(root)) throw new IOException("Cannot restore previous import");
        remove(stage); stage.mkdirs();
        try {
            Set<String> seen = new HashSet<>();
            try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(archive))) {
                ZipEntry item; byte[] buffer = new byte[131072];
                while ((item = zip.getNextEntry()) != null) {
                    String name = item.getName(); Entry spec = expected.get(name);
                    if (spec == null || item.isDirectory() || !seen.add(name))
                        throw new IOException("Unexpected or duplicate file: " + name);
                    File file = new File(stage, name);
                    if (!file.getCanonicalPath().startsWith(stage.getCanonicalPath() + File.separator))
                        throw new IOException("Invalid archive path");
                    file.getParentFile().mkdirs();
                    MessageDigest digest = MessageDigest.getInstance("SHA-256"); long count = 0;
                    try (OutputStream out = new BufferedOutputStream(new FileOutputStream(file))) {
                        int n;
                        while ((n = zip.read(buffer)) != -1) {
                            count += n;
                            if (count > spec.size) throw new IOException("Oversized file: " + name);
                            digest.update(buffer, 0, n); out.write(buffer, 0, n);
                        }
                    }
                    if (count != spec.size || !hex(digest.digest()).equals(spec.hash))
                        throw new IOException("Incompatible or damaged file: " + name);
                }
            }
            if (seen.size() != expected.size()) throw new IOException("This archive is missing required game files");
            try (Writer writer = new FileWriter(new File(stage, "verified"))) { writer.write(manifestHash); }
            remove(old);
            if (root.exists() && !root.renameTo(old)) throw new IOException("Cannot replace previous import");
            if (!stage.renameTo(root)) {
                if (old.exists()) old.renameTo(root);
                throw new IOException("Cannot finish import");
            }
            remove(old);
        } finally { remove(stage); }
    }
    private static void remove(File file) throws IOException {
        if (!file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) remove(child);
        if (!file.delete()) throw new IOException("Cannot remove " + file.getName());
    }
    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder();
        for (byte b : bytes) text.append(String.format(Locale.ROOT, "%02x", b & 255));
        return text.toString();
    }
}
