package com.unlim8ted.celeste;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import org.json.*;

final class EncryptedImport {
    static void install(Context context, Uri keyFile, GameFiles game, Consumer<String> progress) throws Exception {
        final String assetRoot = "owned-game-encrypted/";
        Set<String> files = new HashSet<>(Arrays.asList(context.getAssets().list("owned-game-encrypted")));
        JSONObject release;
        try (InputStream in = context.getAssets().open(assetRoot + "release-parts.json"); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) {
                if (out.size() + n > 1024 * 1024) throw new IOException("Invalid release manifest");
                out.write(buffer, 0, n);
            }
            release = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
        }
        if (release.getInt("format") != 1) throw new IOException("Unsupported release format");
        JSONObject meta = release.getJSONObject("game");
        if (!meta.getString("encryption").equals("AES-256-GCM-HKDF-SHA256")) throw new IOException("Unsupported game encryption");
        long size = meta.getLong("size"), partSize = meta.getLong("partSize");
        int count = meta.getInt("count");
        JSONArray parts = meta.getJSONArray("parts");
        if (size <= 0 || size > 2_000_000_000L || partSize < 1 || partSize >= 99_999_984 || count < 1 || count > 256 ||
                count != (size + partSize - 1) / partSize || parts.length() != count) throw new IOException("Invalid game part list");
        String name = meta.getString("name");
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,180}") || name.contains("..")) throw new IOException("Invalid game filename");
        // Canonical header matches Python's json.dumps(sort_keys=True, separators=(',', ':')).
        StringBuilder header = new StringBuilder("{");
        String[] keys = {"count", "encryption", "name", "noncePrefix", "partSize", "salt", "sha256", "size"};
        for (int i = 0; i < keys.length; i++) {
            if (i != 0) header.append(',');
            header.append(JSONObject.quote(keys[i])).append(':');
            if (keys[i].equals("count") || keys[i].equals("partSize") || keys[i].equals("size")) header.append(meta.getLong(keys[i]));
            else header.append(JSONObject.quote(meta.getString(keys[i])));
        }
        header.append('}');
        // Check all downloads before spending time decrypting any of them.
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < count; i++) {
            String part = parts.getJSONObject(i).getString("name");
            if (!part.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,180}") || part.contains("..") || !seen.add(part) || !files.contains(part))
                throw new IOException("Missing or invalid game part: " + part);
        }
        progress.accept("Checking your game key file...");
        byte[] key = EncryptedGame.key(context.getContentResolver().openInputStream(keyFile), meta.getString("salt"));
        File decrypted = new File(context.getCacheDir(), "celeste-decrypted-import.zip");
        try {
            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(decrypted))) {
                for (int i = 0; i < count; i++) {
                    JSONObject part = parts.getJSONObject(i);
                    long expectedSize = Math.min(partSize, size - i * partSize) + 16;
                    if (part.getLong("size") != expectedSize) throw new IOException("Invalid part size");
                    progress.accept("Decrypting game files: " + (i + 1) + " / " + count + ". Keep this app open.");
                    EncryptedGame.decryptPart(context.getAssets().open(assetRoot + part.getString("name")), out,
                        key, meta.getString("noncePrefix"), i, header.toString(), expectedSize, part.getString("sha256"));
                }
            }
            if (decrypted.length() != size || !EncryptedGame.hash(decrypted).equals(meta.getString("sha256"))) throw new IOException("Decrypted game checksum failed");
            progress.accept("Installing verified game files...");
            game.install(new FileInputStream(decrypted));
        } finally {
            Arrays.fill(key, (byte)0);
            if (decrypted.exists() && !decrypted.delete()) decrypted.deleteOnExit();
        }
    }
}
