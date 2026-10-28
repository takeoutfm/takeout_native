import Foundation
import Network

/**
 * A loopback HTTP server that VLC plays Takeout videos from. VLC can't send the media token,
 * and after a redirect it keeps requesting the presigned storage URL, which expires after
 * 15 minutes (so a later seek would fail). Instead VLC asks this proxy for every range it
 * wants; the proxy gets a presigned URL from Takeout (with the token, reused for up to
 * 10 minutes, fetched again if storage refuses it) and passes the bytes through. Neither the
 * token nor a presigned URL reaches VLC.
 *
 * It listens on 127.0.0.1 only, and each video is reachable only by a random id while it's
 * registered.
 */
final class StreamProxy: @unchecked Sendable {
    static let shared = StreamProxy()

    private let queue = DispatchQueue(label: "takeout.stream-proxy")
    private var listener: NWListener?
    private var port: UInt16?
    private var waiting: [CheckedContinuation<UInt16, Error>] = []
    private var streams: [String: Stream] = [:]

    /** A registered video: where it is and how to get its presigned URL (used on the queue only). */
    final class Stream: @unchecked Sendable {
        let location: String
        let authorization: String
        var presigned: URL?
        var presignedAt = Date.distantPast

        init(location: String, authorization: String) {
            self.location = location
            self.authorization = authorization
        }
    }

    /** The loopback URL VLC plays [location] from, until [unregister]. */
    func register(location: String, authorization: String) async throws -> (id: String, url: URL) {
        let port = try await listeningPort()
        let id = UUID().uuidString
        queue.sync { streams[id] = Stream(location: location, authorization: authorization) }
        return (id, URL(string: "http://127.0.0.1:\(port)/stream/\(id)")!)
    }

    func unregister(_ id: String) {
        queue.async { self.streams[id] = nil }
    }

    #if DEBUG
    /// Tests: makes [id]'s next request use [url] as if it were a fresh presigned URL.
    func plantPresigned(_ url: URL, for id: String) {
        queue.sync {
            streams[id]?.presigned = url
            streams[id]?.presignedAt = Date()
        }
    }
    #endif

    // MARK: Listening

    private func listeningPort() async throws -> UInt16 {
        try await withCheckedThrowingContinuation { continuation in
            queue.async {
                if let port = self.port { return continuation.resume(returning: port) }
                self.waiting.append(continuation)
                if self.listener == nil { self.startListening() }
            }
        }
    }

    private func startListening() {
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: .ipv4(.loopback), port: .any)
        parameters.allowLocalEndpointReuse = true
        do {
            let listener = try NWListener(using: parameters)
            listener.newConnectionHandler = { [weak self] connection in self?.accept(connection) }
            listener.stateUpdateHandler = { [weak self] state in self?.listenerChanged(state) }
            self.listener = listener
            listener.start(queue: queue)
        } catch {
            fail(error)
        }
    }

    private func listenerChanged(_ state: NWListener.State) {
        switch state {
        case .ready:
            port = listener?.port?.rawValue
            if let port {
                waiting.forEach { $0.resume(returning: port) }
                waiting = []
            }
        case .failed(let error):
            NSLog("Stream proxy stopped: \(error)")
            listener?.cancel()
            listener = nil
            port = nil
            fail(error)
        default:
            break
        }
    }

    private func fail(_ error: Error) {
        waiting.forEach { $0.resume(throwing: error) }
        waiting = []
    }

    // MARK: Requests

    private func accept(_ connection: NWConnection) {
        connection.start(queue: queue)
        readRequest(connection, buffer: Data())
    }

    /// Reads up to the end of the request's headers (VLC sends no body).
    private func readRequest(_ connection: NWConnection, buffer: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 16_384) { [weak self] data, _, done, error in
            guard let self else { return }
            var buffer = buffer
            if let data { buffer.append(data) }
            if let end = buffer.range(of: Data("\r\n\r\n".utf8)) {
                self.handle(String(decoding: buffer[..<end.lowerBound], as: UTF8.self), on: connection)
            } else if error != nil || done || buffer.count > 16_384 {
                connection.cancel()
            } else {
                self.readRequest(connection, buffer: buffer)
            }
        }
    }

    private func handle(_ head: String, on connection: NWConnection) {
        let lines = head.components(separatedBy: "\r\n")
        let parts = lines.first?.split(separator: " ") ?? []
        guard parts.count >= 2, parts[0] == "GET" || parts[0] == "HEAD" else {
            return respond(connection, status: 405)
        }
        let path = parts[1]
        guard path.hasPrefix("/stream/"), let stream = streams[String(path.dropFirst("/stream/".count))] else {
            return respond(connection, status: 404)
        }
        let range = lines.dropFirst()
            .first { $0.lowercased().hasPrefix("range:") }
            .map { $0.dropFirst("range:".count).trimmingCharacters(in: .whitespaces) }
        Transfer(proxy: self, stream: stream, connection: connection, head: parts[0] == "HEAD", range: range).start()
    }

    fileprivate func respond(_ connection: NWConnection, status: Int) {
        let head = "HTTP/1.1 \(status) \(HTTPURLResponse.localizedString(forStatusCode: status))\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        connection.send(content: Data(head.utf8), contentContext: .finalMessage, isComplete: true, completion: .contentProcessed { _ in
            connection.cancel()
        })
    }

    /// A presigned URL for [stream]: the last one if it's under 10 minutes old (they last 15).
    fileprivate func presigned(_ stream: Stream, fresh: Bool, then done: @escaping (URL?) -> Void) {
        if !fresh, let url = stream.presigned, Date().timeIntervalSince(stream.presignedAt) < 600 {
            return done(url)
        }
        Task {
            let url = try? await StreamResolver.resolve(stream.location, authorization: stream.authorization)
            self.queue.async {
                if let url {
                    stream.presigned = url
                    stream.presignedAt = Date()
                }
                done(url)
            }
        }
    }

    fileprivate var delegateQueue: OperationQueue {
        let q = OperationQueue()
        q.underlyingQueue = queue
        q.maxConcurrentOperationCount = 1
        return q
    }
}

/**
 * One request from VLC: fetches the range from storage and writes it to the connection,
 * pausing the download while VLC is behind. Everything runs on the proxy's queue.
 */
private final class Transfer: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let proxy: StreamProxy
    private let stream: StreamProxy.Stream
    private let connection: NWConnection
    private let head: Bool
    private let range: String?
    private var session: URLSession?
    private var task: URLSessionDataTask?
    private var retried = false
    private var unsent = 0
    private var paused = false
    private var finished = false

    private static let pauseAbove = 8 << 20
    private static let resumeBelow = 2 << 20
    /// Storage headers VLC needs to see.
    private static let passed = ["Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "Last-Modified", "ETag"]

    init(proxy: StreamProxy, stream: StreamProxy.Stream, connection: NWConnection, head: Bool, range: String?) {
        self.proxy = proxy
        self.stream = stream
        self.connection = connection
        self.head = head
        self.range = range
    }

    func start(fresh: Bool = false) {
        connection.stateUpdateHandler = { [weak self] state in
            // VLC hangs up to seek elsewhere: stop downloading.
            switch state {
            case .failed, .cancelled: self?.stop()
            default: break
            }
        }
        proxy.presigned(stream, fresh: fresh) { [self] url in
            guard let url, !finished else {
                if !finished { proxy.respond(connection, status: 502) }
                return
            }
            var request = URLRequest(url: url)
            request.httpMethod = head ? "HEAD" : "GET"
            if let range { request.setValue(range, forHTTPHeaderField: "Range") }
            let session = URLSession(configuration: .ephemeral, delegate: self, delegateQueue: proxy.delegateQueue)
            self.session = session
            task = session.dataTask(with: request)
            task?.resume()
        }
    }

    private func stop() {
        guard !finished else { return }
        finished = true
        task?.cancel()
        session?.invalidateAndCancel()
    }

    // The completion-handler form, so it runs on the proxy's queue like the rest.
    func urlSession(
        _ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
    ) {
        guard let http = response as? HTTPURLResponse else { return completionHandler(.cancel) }
        #if DEBUG
        NSLog("Stream proxy: \(head ? "HEAD" : "GET") Range=\(range ?? "-") → \(http.statusCode)\(retried ? " (after a fresh URL)" : "")")
        #endif
        // An expired presigned URL: get a new one and try again, once.
        if http.statusCode == 403 && !retried {
            retried = true
            completionHandler(.cancel)
            session.invalidateAndCancel()
            self.session = nil
            start(fresh: true)
            return
        }
        var head = "HTTP/1.1 \(http.statusCode) \(HTTPURLResponse.localizedString(forStatusCode: http.statusCode))\r\n"
        for name in Self.passed {
            if let value = http.value(forHTTPHeaderField: name) { head += "\(name): \(value)\r\n" }
        }
        head += "Connection: close\r\n\r\n"
        send(Data(head.utf8))
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        send(data)
        if unsent > Self.pauseAbove && !paused {
            paused = true
            dataTask.suspend()
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        session.finishTasksAndInvalidate()
        guard !finished, self.session === session else { return }
        finished = true
        connection.send(content: nil, contentContext: .finalMessage, isComplete: true, completion: .contentProcessed { [connection] _ in
            connection.cancel()
        })
    }

    private func send(_ data: Data) {
        guard !finished else { return }
        unsent += data.count
        connection.send(content: data, completion: .contentProcessed { [weak self] error in
            guard let self else { return }
            self.unsent -= data.count
            if error != nil {
                self.stop()
            } else if self.paused && self.unsent < Self.resumeBelow {
                self.paused = false
                self.task?.resume()
            }
        })
    }
}

/**
 * The presigned storage URL a Takeout location redirects to, requested with the media token
 * (StreamProxy uses it for every range VLC asks for; the URLs expire after 15 minutes).
 */
enum StreamResolver {
    static func resolve(_ location: String, authorization: String?) async throws -> URL {
        guard let url = URL(string: location) else { throw URLError(.badURL) }
        guard let authorization else { return url }
        var request = URLRequest(url: url)
        request.httpMethod = "HEAD"
        request.setValue(authorization, forHTTPHeaderField: "Authorization")
        let (_, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        if (300..<400).contains(http.statusCode),
           let target = http.value(forHTTPHeaderField: "Location"),
           let resolved = URL(string: target, relativeTo: url) {
            return resolved.absoluteURL
        }
        guard (200..<300).contains(http.statusCode) else { throw URLError(.userAuthenticationRequired) }
        // Served directly: VLC can't send the header, so this would fail.
        throw URLError(.userAuthenticationRequired)
    }

    private static let session = URLSession(configuration: .ephemeral, delegate: NoRedirects(), delegateQueue: nil)

    private final class NoRedirects: NSObject, URLSessionTaskDelegate {
        func urlSession(
            _ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
            newRequest request: URLRequest
        ) async -> URLRequest? { nil }
    }
}
