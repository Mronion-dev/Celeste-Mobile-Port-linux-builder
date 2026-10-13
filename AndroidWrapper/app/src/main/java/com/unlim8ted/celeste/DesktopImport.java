package com.unlim8ted.celeste;

import android.content.Context;
import android.net.Uri;
import java.io.*;
import java.util.function.Consumer;

final class DesktopImport {
    static void install(Context context, Uri source, GameFiles game, Consumer<String> progress) throws Exception {
        File archive = File.createTempFile("desktop-game-", ".zip", context.getCacheDir());
        File atlas = File.createTempFile("desktop-atlas-", ".data", context.getCacheDir());
        try {
            try (InputStream in = context.getContentResolver().openInputStream(source);
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(archive))) {
                if (in == null) throw new IOException("Cannot open the selected ZIP");
                byte[] buffer = new byte[131072]; long total = 0, reported = -1; int count;
                while ((count = in.read(buffer)) != -1) {
                    total += count;
                    if (total > 8_000_000_000L) throw new IOException("ZIP is too large. Select Gameplay0.data directly instead.");
                    out.write(buffer, 0, count);
                    long mib = total / 1048576;
                    if (mib / 8 != reported) { reported = mib / 8; progress.accept("Reading desktop ZIP · " + mib + " MiB"); }
                }
            }
            progress.accept("Finding your game atlas…");
            if (DesktopGameArchive.extractAtlas(archive, atlas)) {
                // Once found, discard the potentially multi-GB ZIP before decrypting.
                if (!archive.delete()) throw new IOException("Could not release temporary ZIP storage");
                EncryptedImport.install(context, Uri.fromFile(atlas), game, progress);
            } else {
                progress.accept("Installing prepared port package…");
                game.install(new FileInputStream(archive));
            }
        } finally {
            if (archive.exists()) archive.delete();
            if (atlas.exists()) atlas.delete();
        }
    }
}
