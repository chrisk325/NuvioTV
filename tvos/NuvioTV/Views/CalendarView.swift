import SwiftUI
import NuvioShared

/// Upcoming (and last week's) episodes of the shows you follow, grouped by day.
struct CalendarView: View {
    @EnvironmentObject private var app: AppModel
    @State private var entries: [CalendarEntry]?

    var body: some View {
        Group {
            if let entries {
                if entries.isEmpty {
                    MessageView(title: "Nothing scheduled",
                                message: "Shows in your library, Continue Watching or Trakt watchlist will appear here when new episodes air.",
                                systemImage: "calendar")
                } else {
                    List {
                        ForEach(groupedDays(entries), id: \.title) { group in
                            Section(group.title) {
                                ForEach(Array(group.entries.enumerated()), id: \.offset) { _, entry in
                                    NavigationLink(value: Route.detail(entry.show)) {
                                        HStack(spacing: 24) {
                                            RemoteImage(url: entry.video.thumbnail ?? entry.show.backdropUrl)
                                                .frame(width: 240, height: 135)
                                                .clipShape(RoundedRectangle(cornerRadius: 10))
                                            VStack(alignment: .leading, spacing: 6) {
                                                Text(entry.show.name).font(.headline)
                                                Text("\(entry.video.episodeLabel ?? "") · \(entry.video.title)")
                                                    .font(.callout).foregroundStyle(.secondary)
                                                Text(LiveTvFormat.time(entry.releaseEpochMs)).font(.caption).foregroundStyle(.secondary)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                LoadingView(label: "Building your calendar…")
            }
        }
        .navigationTitle("Calendar")
        .task {
            guard entries == nil else { return }
            entries = (try? await app.core.releaseCalendar(daysBack: 7, daysAhead: 60)) ?? []
        }
    }

    private struct DayGroup {
        let title: String
        let entries: [CalendarEntry]
    }

    private func groupedDays(_ entries: [CalendarEntry]) -> [DayGroup] {
        let formatter = DateFormatter()
        formatter.dateStyle = .full
        formatter.timeStyle = .none
        var order: [String] = []
        var map: [String: [CalendarEntry]] = [:]
        for entry in entries {
            let date = Date(timeIntervalSince1970: TimeInterval(entry.releaseEpochMs) / 1000)
            let key = Calendar.current.isDateInToday(date) ? "Today" : formatter.string(from: date)
            if map[key] == nil { order.append(key) }
            map[key, default: []].append(entry)
        }
        return order.map { DayGroup(title: $0, entries: map[$0] ?? []) }
    }
}
