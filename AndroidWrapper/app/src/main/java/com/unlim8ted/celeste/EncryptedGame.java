package com.unlim8ted.celeste;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Crypto shared with unlock_release.py. Plaintext is committed only after all tags pass. */
final class EncryptedGame {
    static byte[] key(InputStream file, String saltHex) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long length = 0;
        try (InputStream in = file) {
            byte[] buffer = new byte[131072]; int n;
            while ((n = in.read(buffer)) != -1) {
                length += n;
                if (length > 100_000_000) throw new IOException("The selected key file is too large");
                digest.update(buffer, 0, n);
            }
        }
        if (length == 0) throw new IOException("The selected key file is empty");
        byte[] salt = unhex(saltHex);
        if (salt.length != 32) throw new IOException("Invalid encryption salt");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] extracted = mac.doFinal(digest.digest());
        mac.init(new SecretKeySpec(extracted, "HmacSHA256"));
        mac.update("celeste-mobile-file-unlock-v1".getBytes(StandardCharsets.UTF_8));
        return mac.doFinal(new byte[] {1});
    }
    static void decryptPart(InputStream input, OutputStream output, byte[] key, String prefix,
                            int index, String header, long size, String expectedHash) throws Exception {
        if (size < 17 || size >= 100_000_000) throw new IOException("Invalid encrypted part size");
        byte[] nonce = unhex(prefix);
        if (nonce.length != 8) throw new IOException("Invalid encryption nonce");
        nonce = java.util.Arrays.copyOf(nonce, 12);
        for (int j = 0; j < 4; j++) nonce[8 + j] = (byte)(index >>> (24 - j * 8));
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(("celeste-mobile-parts-v1\0" + header).getBytes(StandardCharsets.UTF_8));
        cipher.updateAAD(new byte[] {(byte)(index >>> 24), (byte)(index >>> 16), (byte)(index >>> 8), (byte)index});
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long count = 0;
        try (InputStream in = input) {
            byte[] buffer = new byte[131072]; int n;
            while ((n = in.read(buffer)) != -1) {
                count += n;
                if (count > size) throw new IOException("Oversized encrypted part");
                digest.update(buffer, 0, n);
                byte[] plain = cipher.update(buffer, 0, n);
                if (plain != null) output.write(plain);
            }
        }
        if (count != size || !hex(digest.digest()).equals(expectedHash)) throw new IOException("Missing or damaged encrypted part");
        try { output.write(cipher.doFinal()); }
        catch (javax.crypto.AEADBadTagException error) {
            throw new IOException("Wrong key file or incompatible game version. Select the original Gameplay0.data from your Celeste installation.");
        }
    }
    static String hash(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[131072]; int n;
            while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        return hex(digest.digest());
    }
    private static byte[] unhex(String text) throws IOException {
        if (!text.matches("(?:[a-f0-9]{2})+")) throw new IOException("Invalid encryption parameters");
        byte[] result = new byte[text.length() / 2];
        for (int i = 0; i < result.length; i++) result[i] = (byte)Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
        return result;
    }
    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder();
        for (byte b : bytes) text.append(String.format(Locale.ROOT, "%02x", b & 255));
        return text.toString();
    }
}
