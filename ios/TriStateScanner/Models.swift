import Foundation

struct LatestCallResponse: Codable {
    let latest_id: String?
}

struct CallQueueResponse: Codable {
    let calls: [ScannerCall]
    let latest_id: String?
}

struct ScannerCall: Codable, Identifiable {
    let id: String
    let audio_url: String?
    let received_at: String?
    let talkgroup: String?
    let talkgroupLabel: String?
    let talkgroupGroup: String?
    let agency: String?
    let category: String?
    let frequency: String?
    let source: String?
    let transcription_status: String?
    let transcript: String?
}

struct TalkgroupListResponse: Codable {
    let talkgroups: [TalkgroupOption]
}

struct TalkgroupOption: Codable, Identifiable, Hashable {
    var id: Int { talkgroup_id }
    let talkgroup_id: Int
    let name: String
    let agency: String?
    let category: String?
    let source: String?
}

struct AnnouncementListResponse: Codable {
    let announcements: [Announcement]
}

struct Announcement: Codable, Identifiable, Hashable {
    let id: Int
    let title: String
    let message: String
    let starts_at: String?
    let expires_at: String?
    let created_at: String?
    let updated_at: String?

    var fingerprint: String {
        "\(id)|\(title)|\(message)|\(starts_at ?? "")|\(updated_at ?? "")"
    }
}

struct ListenerStats: Codable {
    let listeners: Int?
    let peak: Int?
    let web: Int?
    let android: Int?
    let ios: Int?
}

struct ListenerEventBody: Codable {
    let session_id: String
    let platform: String
}

struct SSEEnvelope: Codable {
    let type: String
    let payload: [String: String]?
    let timestamp: String?
}

struct RecentTranscript: Identifiable {
    let id: String
    let label: String
    let text: String
}
