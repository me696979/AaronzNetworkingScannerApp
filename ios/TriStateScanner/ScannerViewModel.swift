import Foundation
import AVFoundation
import SwiftUI

@MainActor
final class ScannerViewModel: ObservableObject {
    @Published var running = false
    @Published var status = "Press Start to begin"
    @Published var nowPlaying = "Scanning…"
    @Published var details = ""
    @Published var talkgroups: [TalkgroupOption] = []
    @Published var blockedTalkgroups: Set<Int> = []
    @Published var announcements: [Announcement] = []
    @Published var recentTranscripts: [RecentTranscript] = []
    @Published var listenerText = "Listeners: --"
    @Published var showAnnouncementPopup = false
    @Published var popupAnnouncements: [Announcement] = []

    private let api = APIClient.shared
    private let sse = SSEClient()
    private let player = AVPlayer()
    private let sessionID = "ios-\(UUID().uuidString)"
    private var scannerTask: Task<Void, Never>?
    private var heartbeatTask: Task<Void, Never>?
    private var statsTask: Task<Void, Never>?
    private var cursor: String?
    private var startedAt = Date()
    private var generation = 0
    private var pendingTranscriptIDs: [(String, String)] = []

    init() {
        if let saved = UserDefaults.standard.array(forKey: "blocked_talkgroups") as? [Int] {
            blockedTalkgroups = Set(saved)
        }

        Task {
            await loadTalkgroups()
            await loadAnnouncements(showPopup: true)
            await loadStats()
        }

        startSSE()
        startStatsLoop()
    }

    func startScanner() {
        guard !running else { return }
        running = true
        generation += 1
        let currentGeneration = generation
        startedAt = Date()
        cursor = nil
        status = "Starting at live edge…"
        nowPlaying = "Scanning…"
        details = ""

        configureAudioSession()
        startHeartbeatLoop()

        scannerTask?.cancel()
        scannerTask = Task {
            do {
                let latest = try await api.get("/api/call-queue/latest", as: LatestCallResponse.self)
                guard running, currentGeneration == generation else { return }
                cursor = latest.latest_id
                status = "Waiting for next call…"
            } catch {
                status = "Server connection failed - retrying…"
            }

            while !Task.isCancelled && running && currentGeneration == generation {
                await pollCalls(generation: currentGeneration)
                try? await Task.sleep(nanoseconds: 1_000_000_000)
            }
        }
    }

    func stopScanner() {
        running = false
        generation += 1
        scannerTask?.cancel()
        scannerTask = nil
        heartbeatTask?.cancel()
        heartbeatTask = nil
        player.pause()
        player.replaceCurrentItem(with: nil)
        cursor = nil
        status = "Stopped"
        nowPlaying = "Scanning…"
        details = ""
        Task { try? await api.post("/api/listeners/leave", body: ListenerEventBody(session_id: sessionID, platform: "ios")) }
    }

    func setTalkgroup(_ id: Int, enabled: Bool) {
        if enabled {
            blockedTalkgroups.remove(id)
        } else {
            blockedTalkgroups.insert(id)
        }
        UserDefaults.standard.set(Array(blockedTalkgroups), forKey: "blocked_talkgroups")
    }

    func enableAllTalkgroups() {
        blockedTalkgroups.removeAll()
        UserDefaults.standard.set([], forKey: "blocked_talkgroups")
    }

    func dismissAnnouncementPopup() {
        var seen = Set(UserDefaults.standard.stringArray(forKey: "seen_announcements") ?? [])
        popupAnnouncements.forEach { seen.insert($0.fingerprint) }
        UserDefaults.standard.set(Array(seen.suffix(100)), forKey: "seen_announcements")
        popupAnnouncements = []
        showAnnouncementPopup = false
    }

    private func pollCalls(generation currentGeneration: Int) async {
        guard running, currentGeneration == generation else { return }

        if cursor == nil {
            do {
                let latest = try await api.get("/api/call-queue/latest", as: LatestCallResponse.self)
                cursor = latest.latest_id
                status = "Waiting for next call…"
            } catch {
                status = "Waiting for server…"
            }
            return
        }

        guard let cursor else { return }
        let encoded = cursor.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? cursor

        do {
            let response = try await api.get("/api/call-queue?limit=20&after=\(encoded)", as: CallQueueResponse.self)
            for call in response.calls {
                guard running, currentGeneration == generation else { return }
                self.cursor = call.id

                if let tg = Int(call.talkgroup ?? ""), blockedTalkgroups.contains(tg) {
                    continue
                }

                if let received = parseISO(call.received_at) {
                    if received < startedAt { continue }
                    if Date().timeIntervalSince(received) > 20 { continue }
                }

                await play(call, generation: currentGeneration)
            }

            if let latest = response.latest_id, !latest.isEmpty {
                self.cursor = latest
            }
        } catch {
            status = "Call queue temporarily unavailable"
        }
    }

    private func play(_ call: ScannerCall, generation currentGeneration: Int) async {
        guard running, currentGeneration == generation,
              let audioPath = call.audio_url,
              let url = api.audioURL(for: audioPath) else { return }

        let label = call.talkgroupLabel ?? "Talkgroup \(call.talkgroup ?? "")"
        nowPlaying = label
        let frequencyText = formatFrequency(call.frequency)
        details = "TGID \(call.talkgroup ?? "")  •  \(frequencyText)  •  Radio \(call.source ?? "")"
        status = "Playing"

        if !call.id.isEmpty {
            pendingTranscriptIDs.append((call.id, label))
            if pendingTranscriptIDs.count > 20 {
                pendingTranscriptIDs.removeFirst(pendingTranscriptIDs.count - 20)
            }
        }

        let item = AVPlayerItem(url: url)
        player.replaceCurrentItem(with: item)
        player.play()

        await waitForPlaybackEnd(item)

        if running && currentGeneration == generation {
            nowPlaying = "Scanning…"
            details = ""
            status = "Waiting for next call…"
        }

        await updatePendingTranscripts()
    }

    private func waitForPlaybackEnd(_ item: AVPlayerItem) async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            var ended: NSObjectProtocol?
            var failed: NSObjectProtocol?
            var finished = false

            func finish() {
                guard !finished else { return }
                finished = true
                if let ended { NotificationCenter.default.removeObserver(ended) }
                if let failed { NotificationCenter.default.removeObserver(failed) }
                continuation.resume()
            }

            ended = NotificationCenter.default.addObserver(
                forName: .AVPlayerItemDidPlayToEndTime,
                object: item,
                queue: .main
            ) { _ in finish() }

            failed = NotificationCenter.default.addObserver(
                forName: .AVPlayerItemFailedToPlayToEndTime,
                object: item,
                queue: .main
            ) { _ in finish() }
        }
    }

    private func updatePendingTranscripts() async {
        guard let next = pendingTranscriptIDs.first else { return }
        do {
            let detail = try await api.get("/api/call-detail/\(next.0)", as: ScannerCall.self)
            switch detail.transcription_status {
            case "complete":
                pendingTranscriptIDs.removeFirst()
                if let text = detail.transcript?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty {
                    recentTranscripts.insert(RecentTranscript(id: next.0, label: next.1, text: text), at: 0)
                    recentTranscripts = Array(recentTranscripts.prefix(3))
                }
            case "filtered", "error":
                pendingTranscriptIDs.removeFirst()
            default:
                break
            }
        } catch {
        }
    }

    func loadTalkgroups() async {
        do {
            let response = try await api.get("/api/public/talkgroups", as: TalkgroupListResponse.self)
            talkgroups = response.talkgroups
        } catch {
        }
    }

    func loadAnnouncements(showPopup: Bool) async {
        do {
            let response = try await api.get("/api/public/announcements", as: AnnouncementListResponse.self)
            announcements = response.announcements
            guard showPopup else { return }

            let seen = Set(UserDefaults.standard.stringArray(forKey: "seen_announcements") ?? [])
            let unseen = announcements.filter { !seen.contains($0.fingerprint) }
            if !unseen.isEmpty {
                popupAnnouncements = unseen
                showAnnouncementPopup = true
            }
        } catch {
        }
    }

    private func startSSE() {
        sse.start { [weak self] event in
            guard let self else { return }
            await MainActor.run {
                switch event.type {
                case "talkgroups_changed":
                    Task { await self.loadTalkgroups() }
                case "announcements_changed":
                    Task { await self.loadAnnouncements(showPopup: true) }
                default:
                    break
                }
            }
        }
    }

    private func startHeartbeatLoop() {
        heartbeatTask?.cancel()
        heartbeatTask = Task {
            while !Task.isCancelled && running {
                try? await api.post("/api/listeners/heartbeat", body: ListenerEventBody(session_id: sessionID, platform: "ios"))
                try? await Task.sleep(nanoseconds: 10_000_000_000)
            }
        }
    }

    private func startStatsLoop() {
        statsTask?.cancel()
        statsTask = Task {
            while !Task.isCancelled {
                await loadStats()
                try? await Task.sleep(nanoseconds: 15_000_000_000)
            }
        }
    }

    private func loadStats() async {
        do {
            let stats = try await api.get("/api/listeners/stats", as: ListenerStats.self)
            listenerText = "Listeners: \(stats.listeners ?? 0)   Peak: \(stats.peak ?? 0)"
        } catch {
        }
    }

    private func configureAudioSession() {
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio)
            try AVAudioSession.sharedInstance().setActive(true)
        } catch {
        }
    }

    private func parseISO(_ value: String?) -> Date? {
        guard let value else { return nil }
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter.date(from: value) ?? ISO8601DateFormatter().date(from: value)
    }

    private func formatFrequency(_ value: String?) -> String {
        guard let value, let hz = Double(value) else { return value ?? "" }
        return String(format: "%.4f MHz", hz / 1_000_000.0)
    }
}
