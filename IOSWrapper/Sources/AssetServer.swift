import Foundation
import Network
import UIKit

/// Loopback HTTP is required for real COOP/COEP headers and WASM streaming.
final class AssetServer {
    private var listener: NWListener?
    private let queue = DispatchQueue(label: "celeste.assets", attributes: .concurrent)
    private let runtime: URL
    private let owned: URL
    init(runtime: URL, owned: URL) { self.runtime = runtime; self.owned = owned }
    func start(ready: @escaping (Result<URL, Error>) -> Void) throws {
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: 8080)
        let server = try NWListener(using: parameters)
        listener = server
        server.stateUpdateHandler = { state in
            switch state {
            case .ready: ready(.success(URL(string: "http://127.0.0.1:8080/")!))
            case .failed(let error): ready(.failure(error))
            default: break
            }
        }
        server.newConnectionHandler = { [weak self] connection in
            guard let self = self else { connection.cancel(); return }
            connection.start(queue: self.queue)
            self.receive(connection, buffer: Data())
        }
        server.start(queue: queue)
    }
    private func receive(_ connection: NWConnection, buffer: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 16384) { [weak self] data, _, done, error in
            guard let self = self, error == nil else { connection.cancel(); return }
            var all = buffer
            all.append(data ?? Data())
            if all.count > 32768 { connection.cancel(); return }
            if let text = String(data: all, encoding: .utf8), text.contains("\r\n\r\n") {
                self.handle(connection, request: text)
            } else if done { connection.cancel() }
            else { self.receive(connection, buffer: all) }
        }
    }
    private func header(_ status: Int, _ mime: String, _ size: UInt64) -> Data {
        Data("HTTP/1.1 \(status) \(status == 200 ? "OK" : "Error")\r\nContent-Type: \(mime)\r\nContent-Length: \(size)\r\nCross-Origin-Opener-Policy: same-origin\r\nCross-Origin-Embedder-Policy: require-corp\r\nCross-Origin-Resource-Policy: same-origin\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".utf8)
    }
    private func text(_ connection: NWConnection, status: Int = 200, _ value: String) {
        let bytes = Data(value.utf8)
        connection.send(content: header(status, "text/plain; charset=utf-8", UInt64(bytes.count)) + bytes,
            completion: .contentProcessed { _ in connection.cancel() })
    }
    private func handle(_ connection: NWConnection, request: String) {
        let words = request.components(separatedBy: "\r\n")[0].split(separator: " ")
        guard words.count == 3, words[0] == "GET" || words[0] == "HEAD",
              let url = URLComponents(string: String(words[1])), url.host == nil,
              !url.path.contains(".."), !url.path.contains("\\") else { text(connection, status: 400, "Bad request"); return }
        if url.path == "/__android_port_log" { text(connection, ""); return }
        if url.path.hasPrefix("/__android_bridge/") {
            let command = String(url.path.dropFirst("/__android_bridge/".count))
            if command == "openUrl", let value = url.queryItems?.first(where: { $0.name == "url" })?.value,
               let external = URL(string: value), ["https", "http"].contains(external.scheme?.lowercased() ?? "") {
                DispatchQueue.main.async { UIApplication.shared.open(external) }
            } else if command == "haptic" {
                DispatchQueue.main.async { UIImpactFeedbackGenerator(style: .medium).impactOccurred() }
            }
            text(connection, ""); return
        }
        let path = url.path == "/" ? "index.html" : String(url.path.dropFirst())
        let root = path.hasPrefix("celeste/") || path == "_framework/data/data.data" ? owned : runtime
        var files = [root.appendingPathComponent(path)]
        if path.hasSuffix(".wasm") && FileManager.default.fileExists(atPath: root.appendingPathComponent(path + "0").path) {
            files = []
            var index = 0
            while FileManager.default.fileExists(atPath: root.appendingPathComponent(path + String(index)).path) {
                files.append(root.appendingPathComponent(path + String(index))); index += 1
            }
        }
        var handles: [FileHandle] = []
        do {
            var size: UInt64 = 0
            for file in files {
                let values = try file.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey])
                guard values.isRegularFile == true else { throw ImportError.invalid("Not a file") }
                size += UInt64(values.fileSize ?? 0)
                handles.append(try FileHandle(forReadingFrom: file))
            }
            let ext = (path as NSString).pathExtension.lowercased()
            let mime = ["html":"text/html; charset=utf-8", "js":"application/javascript", "mjs":"application/javascript",
                        "json":"application/json", "wasm":"application/wasm", "css":"text/css", "png":"image/png",
                        "svg":"image/svg+xml", "woff2":"font/woff2"][ext] ?? "application/octet-stream"
            let streams = handles
            connection.send(content: header(200, mime, size), completion: .contentProcessed { [weak self] error in
                if error != nil || words[0] == "HEAD" {
                    streams.forEach { try? $0.close() }; connection.cancel()
                } else { self?.stream(connection, files: streams, index: 0) }
            })
        } catch {
            handles.forEach { try? $0.close() }
            text(connection, status: 404, "Not found")
        }
    }
    private func stream(_ connection: NWConnection, files: [FileHandle], index: Int) {
        guard index < files.count else { connection.cancel(); return }
        do {
            let bytes = try files[index].read(upToCount: 131072) ?? Data()
            if bytes.isEmpty { try files[index].close(); stream(connection, files: files, index: index + 1); return }
            connection.send(content: bytes, completion: .contentProcessed { [weak self] error in
                if error != nil { files.forEach { try? $0.close() }; connection.cancel() }
                else { self?.stream(connection, files: files, index: index) }
            })
        } catch { files.forEach { try? $0.close() }; connection.cancel() }
    }
    deinit { listener?.cancel() }
}
