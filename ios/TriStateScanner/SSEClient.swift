import Foundation

final class SSEClient {
    private var task: Task<Void, Never>?

    func start(onEvent: @escaping @Sendable (SSEEnvelope) async -> Void) {
        stop()

        task = Task.detached(priority: .background) {
            while !Task.isCancelled {
                do {
                    let url = APIClient.shared.baseURL.appendingPathComponent("api/public/events")
                    var request = URLRequest(url: url)
                    request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
                    request.cachePolicy = .reloadIgnoringLocalCacheData

                    let (bytes, response) = try await URLSession.shared.bytes(for: request)
                    guard let http = response as? HTTPURLResponse,
                          (200..<300).contains(http.statusCode) else {
                        throw URLError(.badServerResponse)
                    }

                    var eventName = ""
                    var dataLines: [String] = []

                    for try await line in bytes.lines {
                        if Task.isCancelled { return }

                        if line.hasPrefix("event:") {
                            eventName = String(line.dropFirst(6)).trimmingCharacters(in: .whitespaces)
                        } else if line.hasPrefix("data:") {
                            dataLines.append(String(line.dropFirst(5)).trimmingCharacters(in: .whitespaces))
                        } else if line.isEmpty {
                            if eventName == "scanner-update", !dataLines.isEmpty {
                                let joined = dataLines.joined(separator: "\n")
                                if let data = joined.data(using: .utf8),
                                   let envelope = try? JSONDecoder().decode(SSEEnvelope.self, from: data) {
                                    await onEvent(envelope)
                                }
                            }
                            eventName = ""
                            dataLines.removeAll(keepingCapacity: true)
                        }
                    }
                } catch {
                    if Task.isCancelled { return }
                }

                try? await Task.sleep(nanoseconds: 2_000_000_000)
            }
        }
    }

    func stop() {
        task?.cancel()
        task = nil
    }

    deinit {
        stop()
    }
}
