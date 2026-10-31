import Foundation
import SwiftUI

struct TVApp: Decodable, Identifiable {
    let id: String
    let name: String
}

struct TVInput: Decodable, Identifiable {
    let id: String
    let name: String
    let state: Int

    var live: Bool { state == 0 }
}

private struct Status: Decodable {
    let paired: Bool
    let apps: [TVApp]
    let inputs: [TVInput]
}

/// Talks to the Dekho app running on the TV. Same commands as its remote page.
@MainActor
final class TV: ObservableObject {
    @AppStorage("address") var address = TV.bundledAddress
    @Published var apps: [TVApp] = []
    @Published var inputs: [TVInput] = []
    @Published var reachable = false
    @Published var paired = true
    @Published var note = ""

    private static var bundledAddress: String {
        guard let file = Bundle.main.url(forResource: "address", withExtension: "txt"),
              let text = try? String(contentsOf: file, encoding: .utf8) else { return "" }
        return text.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func url(_ path: String) -> URL? {
        URL(string: address.trimmingCharacters(in: CharacterSet(charactersIn: "/ \n")) + "/" + path)
    }

    private func get(_ path: String, body: String? = nil, timeout: TimeInterval = 5) async throws -> Data {
        guard let url = url(path) else { throw URLError(.badURL) }
        var request = URLRequest(url: url, timeoutInterval: timeout)
        if let body {
            request.httpMethod = "POST"
            request.httpBody = Data(body.utf8)
        }
        let (data, response) = try await URLSession.shared.data(for: request)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw URLError(.badServerResponse) }
        return data
    }

    func refresh() async {
        guard !address.isEmpty else {
            reachable = false
            return
        }
        do {
            let status = try JSONDecoder().decode(Status.self, from: await get("status"))
            apps = status.apps
            inputs = status.inputs
            paired = status.paired
            reachable = true
            note = ""
        } catch {
            reachable = false
            note = "Could not reach the TV."
        }
    }

    private func send(_ path: String) {
        Task {
            do {
                _ = try await get(path)
                reachable = true
            } catch {
                reachable = false
                note = "Could not reach the TV."
            }
        }
    }

    func key(_ code: Int) { send("key?c=\(code)") }
    func strip() { send("strip") }
    func brightness() { send("brightness") }
    func open(_ app: TVApp) { send("app?p=" + Self.encoded(app.id)) }
    func open(_ input: TVInput) { send("input?i=" + Self.encoded(input.id)) }

    func type(_ text: String) async -> Bool {
        do {
            _ = try await get("type", body: Self.encoded(text), timeout: 30)
            note = "Sent to the TV."
            return true
        } catch {
            note = "Could not reach the TV."
            return false
        }
    }

    /// Asks the TV to allow the remote. The owner answers on the TV.
    func pair() {
        note = "Watch the TV, it will ask to allow this."
        Task {
            let data = try? await get("pair", timeout: 60)
            let answer = data.flatMap { try? JSONDecoder().decode([String: Bool].self, from: $0) }
            paired = answer?["paired"] == true
            note = paired ? "Remote is ready." : "The TV did not allow it. Try again."
        }
    }

    private static func encoded(_ text: String) -> String {
        text.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? ""
    }
}
