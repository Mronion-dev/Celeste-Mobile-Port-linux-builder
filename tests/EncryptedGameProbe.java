package com.unlim8ted.celeste;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Java/Android crypto interoperability against the actual Python release parts. */
public final class EncryptedGameProbe {
    public static void main(String[] args) throws Exception {
        List<String> spec = Files.readAllLines(Path.of(args[0]));
        byte[] key = EncryptedGame.key(new FileInputStream(args[1]), spec.get(0));
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (OutputStream out = new DigestOutputStream(OutputStream.nullOutputStream(), hash)) {
            for (int i = 4; i < spec.size(); i++) {
                String[] p = spec.get(i).split("\t");
                EncryptedGame.decryptPart(new FileInputStream(Path.of(args[2], p[0]).toFile()), out, key,
                    spec.get(1), i - 4, spec.get(2), Long.parseLong(p[1]), p[2]);
            }
        }
        StringBuilder actual = new StringBuilder();
        for (byte b : hash.digest()) actual.append(String.format("%02x", b & 255));
        if (!actual.toString().equals(spec.get(3))) throw new AssertionError("Plaintext differs from Python release");
        byte[] wrong = EncryptedGame.key(new ByteArrayInputStream(new byte[] {1, 2, 3}), spec.get(0));
        String[] p = spec.get(4).split("\t");
        try {
            EncryptedGame.decryptPart(new FileInputStream(Path.of(args[2], p[0]).toFile()), OutputStream.nullOutputStream(),
                wrong, spec.get(1), 0, spec.get(2), Long.parseLong(p[1]), p[2]);
            throw new AssertionError("Wrong key accepted");
        } catch (IOException expected) {
            if (!expected.getMessage().startsWith("Wrong key file")) throw expected;
        }
        System.out.println("PASS: Java decrypts the full Python payload exactly; wrong key is rejected");
    }
}
