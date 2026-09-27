import Foundation
import CryptoKit
import ZIPFoundation

enum ImportError: LocalizedError {
    case invalid(String)
    var errorDescription: String? { if case .invalid(let message) = self { return message }; return nil }
}

final class GameImport {
    let assets: URL
    let destination: URL
    init(assets: URL, destination: URL) { self.assets = assets; self.destination = destination }
    var ready: Bool {
        guard let manifest = try? Data(contentsOf: assets.appendingPathComponent("game-files.tsv")),
              let marker = try? String(contentsOf: destination.appendingPathComponent("verified"), encoding: .utf8) else { return false }
        return marker == Self.hex(SHA256.hash(data: manifest))
    }
    static func hex<S: Sequence>(_ bytes: S) -> String where S.Element == UInt8 {
        bytes.map { String(format: "%02x", $0) }.joined()
    }
    static func unhex(_ text: String) throws -> Data {
        guard text.count % 2 == 0 else { throw ImportError.invalid("Invalid encryption parameters") }
        let chars = Array(text)
        return try Data(stride(from: 0, to: chars.count, by: 2).map {
            guard let byte = UInt8(String(chars[$0...($0 + 1)]), radix: 16) else { throw ImportError.invalid("Invalid encryption parameters") }
            return byte
        })
    }
    static func hash(_ url: URL) throws -> String {
        let file = try FileHandle(forReadingFrom: url)
        defer { try? file.close() }
        var hash = SHA256()
        while let chunk = try file.read(upToCount: 131072), !chunk.isEmpty { hash.update(data: chunk) }
        return hex(hash.finalize())
    }
    func unlock(_ atlas: URL, progress: @escaping (String) -> Void) throws {
        let fm = FileManager.default
        let scoped = atlas.startAccessingSecurityScopedResource()
        defer { if scoped { atlas.stopAccessingSecurityScopedResource() } }
        let length = try atlas.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        guard length > 0 && length < 100_000_000 else { throw ImportError.invalid("Select the original Gameplay0.data from your Celeste installation") }
        let encrypted = assets.appendingPathComponent("owned-game-encrypted")
        let metadata = try Data(contentsOf: encrypted.appendingPathComponent("release-parts.json"))
        guard let root = try JSONSerialization.jsonObject(with: metadata) as? [String: Any],
              let game = root["game"] as? [String: Any],
              let salt = game["salt"] as? String, let prefix = game["noncePrefix"] as? String,
              let parts = game["parts"] as? [[String: Any]],
              let expectedHash = game["sha256"] as? String,
              let expectedSize = game["size"] as? Int,
              let count = game["count"] as? Int, count == parts.count,
              game["encryption"] as? String == "AES-256-GCM-HKDF-SHA256" else { throw ImportError.invalid("Invalid bundled metadata") }
        let key = HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: try Self.unhex(Self.hash(atlas))),
            salt: try Self.unhex(salt), info: Data("celeste-mobile-file-unlock-v1".utf8), outputByteCount: 32)
        let headerKeys = ["name", "size", "sha256", "partSize", "count", "encryption", "salt", "noncePrefix"]
        let header = game.filter { headerKeys.contains($0.key) }
        let aadHeader = try JSONSerialization.data(withJSONObject: header, options: [.sortedKeys, .withoutEscapingSlashes])
        let scratch = destination.deletingLastPathComponent().appendingPathComponent("import-" + UUID().uuidString)
        try fm.createDirectory(at: scratch, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: scratch) }
        let zip = scratch.appendingPathComponent("game.zip")
        fm.createFile(atPath: zip.path, contents: nil)
        let output = try FileHandle(forWritingTo: zip)
        defer { try? output.close() }
        for (index, part) in parts.enumerated() {
            try autoreleasepool {
                guard let name = part["name"] as? String, !name.contains("/"), !name.contains("\\"), !name.contains(".."),
                      let size = part["size"] as? Int, size > 16, size < 100_000_000,
                      let sha = part["sha256"] as? String else { throw ImportError.invalid("Invalid encrypted part") }
                let data = try Data(contentsOf: encrypted.appendingPathComponent(name))
                guard data.count == size, Self.hex(SHA256.hash(data: data)) == sha else { throw ImportError.invalid("Damaged encrypted part") }
                var ordinal = UInt32(index).bigEndian
                let indexBytes = withUnsafeBytes(of: &ordinal) { Data($0) }
                let nonce = try AES.GCM.Nonce(data: Self.unhex(prefix) + indexBytes)
                let box = try AES.GCM.SealedBox(nonce: nonce, ciphertext: data.dropLast(16), tag: data.suffix(16))
                let aad = Data("celeste-mobile-parts-v1\0".utf8) + aadHeader + indexBytes
                do { try output.write(contentsOf: AES.GCM.open(box, using: key, authenticating: aad)) }
                catch { throw ImportError.invalid("Wrong or incompatible atlas. Select your original Gameplay0.data.") }
                progress("Decrypting game files: \(index + 1)/\(parts.count)")
            }
        }
        try output.close()
        guard try Self.hash(zip) == expectedHash,
              (try zip.resourceValues(forKeys: [.fileSizeKey]).fileSize) == expectedSize else { throw ImportError.invalid("Game archive verification failed") }
        let stage = scratch.appendingPathComponent("verified-game")
        try fm.createDirectory(at: stage, withIntermediateDirectories: true)
        let manifest = try Data(contentsOf: assets.appendingPathComponent("game-files.tsv"))
        let lines = String(decoding: manifest, as: UTF8.self).split(separator: "\n")
        let archive = try Archive(url: zip, accessMode: .read)
        for line in lines {
            let fields = line.split(separator: "\t").map(String.init)
            guard fields.count == 3, !fields[2].contains(".."), !fields[2].hasPrefix("/"),
                  !fields[2].contains("\\"), let entry = archive[fields[2]], entry.type == .file,
                  entry.uncompressedSize == UInt64(fields[1]) else { throw ImportError.invalid("Invalid game manifest") }
            let target = stage.appendingPathComponent(fields[2])
            try fm.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
            _ = try archive.extract(entry, to: target)
            guard try Self.hash(target) == fields[0] else { throw ImportError.invalid("Game file verification failed") }
        }
        try Self.hex(SHA256.hash(data: manifest)).write(to: stage.appendingPathComponent("verified"), atomically: true, encoding: .utf8)
        if fm.fileExists(atPath: destination.path) {
            _ = try fm.replaceItemAt(destination, withItemAt: stage)
        } else { try fm.moveItem(at: stage, to: destination) }
    }
}
