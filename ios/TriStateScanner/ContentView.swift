import SwiftUI

struct ContentView: View {
    @StateObject var model: ScannerViewModel
    @State private var showTalkgroups = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    header
                    playerCard
                    announcementSection
                    transcriptSection
                }
                .padding()
            }
            .background(Color.black.ignoresSafeArea())
            .navigationBarHidden(true)
        }
        .preferredColorScheme(.dark)
        .sheet(isPresented: $showTalkgroups) {
            TalkgroupChooser(model: model)
        }
        .alert("📢 Scanner Announcement", isPresented: $model.showAnnouncementPopup) {
            Button("OK") {
                model.dismissAnnouncementPopup()
            }
        } message: {
            Text(model.popupAnnouncements.map { "\($0.title)\n\($0.message)" }.joined(separator: "\n\n"))
        }
    }

    private var header: some View {
        VStack(spacing: 4) {
            Text("Tri-State Scanner")
                .font(.largeTitle.bold())
                .foregroundStyle(.green)
            Text("Live public-safety radio feed")
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
    }

    private var playerCard: some View {
        VStack(spacing: 12) {
            HStack {
                Circle()
                    .fill(model.running ? Color.green : Color.gray)
                    .frame(width: 10, height: 10)
                Text(model.status)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Spacer()
            }

            Text(model.nowPlaying)
                .font(.title2.bold())
                .foregroundStyle(model.running ? .green : .secondary)
                .frame(maxWidth: .infinity, alignment: .leading)

            if !model.details.isEmpty {
                Text(model.details)
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }

            Button(model.running ? "STOP SCANNER" : "START SCANNER") {
                if model.running {
                    model.stopScanner()
                } else {
                    model.startScanner()
                }
            }
            .buttonStyle(.borderedProminent)
            .tint(model.running ? .red : .green)
            .frame(maxWidth: .infinity)

            Button {
                showTalkgroups = true
            } label: {
                let enabled = model.talkgroups.filter { !model.blockedTalkgroups.contains($0.id) }.count
                Text(model.talkgroups.isEmpty ? "CHOOSE TALKGROUPS" : "TALKGROUPS: \(enabled)/\(model.talkgroups.count) ENABLED")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)

            Text(model.listenerText)
                .font(.caption.monospaced())
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding()
        .background(Color.green.opacity(0.08))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Color.green.opacity(0.35)))
        .clipShape(RoundedRectangle(cornerRadius: 14))
    }

    @ViewBuilder
    private var announcementSection: some View {
        if !model.announcements.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                Label("Announcements", systemImage: "megaphone.fill")
                    .font(.headline)
                    .foregroundStyle(.yellow)

                ForEach(model.announcements) { item in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(item.title).bold()
                        Text(item.message)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    if item.id != model.announcements.last?.id {
                        Divider()
                    }
                }
            }
            .padding()
            .background(Color.yellow.opacity(0.08))
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(Color.yellow.opacity(0.35)))
            .clipShape(RoundedRectangle(cornerRadius: 14))
        }
    }

    private var transcriptSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Recent AI Transcripts")
                .font(.headline)
                .foregroundStyle(.green)

            if model.recentTranscripts.isEmpty {
                Text("Waiting for completed transcripts…")
                    .foregroundStyle(.secondary)
            } else {
                ForEach(model.recentTranscripts) { item in
                    VStack(alignment: .leading, spacing: 3) {
                        Text(item.label)
                            .font(.subheadline.bold())
                            .foregroundStyle(.green)
                        Text(item.text)
                            .font(.body)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    Divider()
                }
            }
        }
        .padding()
        .background(Color.green.opacity(0.05))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Color.green.opacity(0.25)))
        .clipShape(RoundedRectangle(cornerRadius: 14))
    }
}

struct TalkgroupChooser: View {
    @ObservedObject var model: ScannerViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var search = ""

    private var filtered: [TalkgroupOption] {
        guard !search.isEmpty else { return model.talkgroups }
        return model.talkgroups.filter {
            $0.name.localizedCaseInsensitiveContains(search) ||
            ($0.agency ?? "").localizedCaseInsensitiveContains(search) ||
            String($0.id).contains(search)
        }
    }

    var body: some View {
        NavigationStack {
            List(filtered) { tg in
                Toggle(isOn: Binding(
                    get: { !model.blockedTalkgroups.contains(tg.id) },
                    set: { model.setTalkgroup(tg.id, enabled: $0) }
                )) {
                    VStack(alignment: .leading) {
                        Text(tg.name)
                        Text("TG \(tg.id)\(tg.agency.map { " • \($0)" } ?? "")")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .searchable(text: $search, prompt: "Search talkgroups")
            .navigationTitle("Choose Talkgroups")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Enable All") {
                        model.enableAllTalkgroups()
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
            .task {
                if model.talkgroups.isEmpty {
                    await model.loadTalkgroups()
                }
            }
        }
    }
}
