package com.unlim8ted.celeste;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Extract only the ownership atlas to a fixed private path, never desktop executables. */
final class DesktopGameArchive {
    static boolean extractAtlas(File archive, File target) throws IOException {
        try (ZipFile zip = new ZipFile(archive)) {
            ZipEntry found = null;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().replace('\\', '/').toLowerCase(Locale.ROOT);
                if (entry.isDirectory() || Arrays.asList(name.split("/")).contains("..")) continue;
                if (name.equals("content/graphics/atlases/gameplay0.data") || name.endsWith("/content/graphics/atlases/gameplay0.data")) {
                    if (found != null) throw new IOException("This ZIP contains multiple Celeste installations. ZIP just one game folder.");
                    found = entry;
                }
            }
            if (found == null) {
                if (zip.getEntry("celeste/Celeste.dll") != null && zip.getEntry("_framework/data/data.data") != null) return false;
                throw new IOException("No Gameplay0.data found. ZIP your Celeste game folder including Content/Graphics/Atlases. Save-only ZIPs belong in Options > Load Save after setup.");
            }
            if (found.getSize() <= 0 || found.getSize() > 100_000_000) throw new IOException("Invalid Gameplay0.data size");
            try (InputStream in = zip.getInputStream(found); OutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
                byte[] buffer = new byte[131072]; long total = 0; int count;
                while ((count = in.read(buffer)) != -1) {
                    total += count;
                    if (total > 100_000_000) throw new IOException("Gameplay0.data is too large");
                    out.write(buffer, 0, count);
                }
                if (total != found.getSize()) throw new IOException("Damaged Gameplay0.data entry");
            }
            return true;
        }
    }
}
