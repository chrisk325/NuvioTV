import SwiftUI
import NuvioShared

/// SwiftUI bindings over the Kotlin AppSettings properties.
@MainActor
final class SettingsBinder: ObservableObject {
    let settings: AppSettings
    init(_ settings: AppSettings) { self.settings = settings }

    func bind<T>(_ get: @escaping (AppSettings) -> T, _ set: @escaping (AppSettings, T) -> Void) -> Binding<T> {
        Binding(
            get: { get(self.settings) },
            set: { value in
                set(self.settings, value)
                self.objectWillChange.send()
            }
        )
    }
}

/// Settings categories, shown in the sidebar (tvOS Settings-app style two-pane layout).
enum SettingsCategory: String, CaseIterable, Identifiable {
    case profiles, player, subtitles, home, metadata, accounts, debrid, servers, about

    var id: String { rawValue }

    var title: String {
        switch self {
        case .profiles: return "Profiles"
        case .player: return "Player"
        case .subtitles: return "Subtitles"
        case .home: return "Home"
        case .metadata: return "Metadata"
        case .accounts: return "Accounts"
        case .debrid: return "Debrid"
        case .servers: return "Media servers"
        case .about: return "About"
        }
    }

    var icon: String {
        switch self {
        case .profiles: return "person.2.fill"
        case .player: return "play.rectangle.fill"
        case .subtitles: return "captions.bubble.fill"
        case .home: return "house.fill"
        case .metadata: return "info.circle.fill"
        case .accounts: return "person.crop.circle.badge.checkmark"
        case .debrid: return "bolt.fill"
        case .servers: return "server.rack"
        case .about: return "questionmark.circle.fill"
        }
    }

    var summary: String {
        switch self {
        case .profiles: return "Who's watching, PINs"
        case .player: return "Engine, HDR, audio, auto-play"
        case .subtitles: return "Languages, style, timing"
        case .home: return "Layout, hero, catalogs"
        case .metadata: return "TMDB and MDBList"
        case .accounts: return "Nuvio, Trakt, Simkl, AniList"
        case .debrid: return "Torbox, Premiumize, Real-Debrid"
        case .servers: return "Jellyfin and Emby"
        case .about: return "Version and credits"
        }
    }
}

struct SettingsView: View {
    @EnvironmentObject private var app: AppModel
    @State private var selected: SettingsCategory = .player

    var body: some View {
        let binder = SettingsBinder(app.settings)
        HStack(spacing: 0) {
            sidebar
                .frame(width: 600)
                .background(Color.white.opacity(0.04))
            VStack(alignment: .leading, spacing: 0) {
                paneHeader
                pane(binder)
                    .id(selected)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .focusSection()
        }
        .background(
            LinearGradient(colors: [Color(white: 0.09), Color(white: 0.03)], startPoint: .topLeading, endPoint: .bottomTrailing)
                .ignoresSafeArea()
        )
        .toolbar(.hidden, for: .navigationBar)
    }

    private var sidebar: some View {
        ScrollView(.vertical) {
            VStack(alignment: .leading, spacing: 14) {
                Text("Settings")
                    .font(.system(size: 52, weight: .bold))
                    .padding(.horizontal, 24)
                    .padding(.bottom, 24)
                ForEach(SettingsCategory.allCases) { category in
                    Button {
                        selected = category
                    } label: {
                        HStack(spacing: 22) {
                            Image(systemName: category.icon)
                                .font(.title3)
                                .frame(width: 48)
                            VStack(alignment: .leading, spacing: 4) {
                                Text(category.title).font(.headline)
                                Text(category.summary).font(.caption).opacity(0.7).lineLimit(1)
                            }
                            Spacer(minLength: 0)
                        }
                        // Like the tvOS Settings app, focusing a category shows it.
                        .background(FocusReporter { selected = category })
                    }
                    .buttonStyle(SidebarCategoryStyle(isSelected: selected == category))
                }
            }
            .padding(.horizontal, 40)
            .padding(.vertical, 60)
        }
        .focusSection()
    }

    private var paneHeader: some View {
        HStack(spacing: 24) {
            Image(systemName: selected.icon)
                .font(.system(size: 40))
                .frame(width: 84, height: 84)
                .background(Color.accentColor.opacity(0.35), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
            VStack(alignment: .leading, spacing: 6) {
                Text(selected.title).font(.title2.bold())
                Text(selected.summary).font(.callout).foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal, 70)
        .padding(.top, 70)
        .padding(.bottom, 10)
    }

    @ViewBuilder
    private func pane(_ binder: SettingsBinder) -> some View {
        switch selected {
        case .profiles: ProfilesSettingsView()
        case .player: PlayerSettingsPane(binder: binder)
        case .subtitles: SubtitleSettingsPane(binder: binder)
        case .home: HomeSettingsPane(binder: binder)
        case .metadata: MetadataSettingsPane(binder: binder)
        case .accounts: AccountsView()
        case .debrid: DebridSettingsView()
        case .servers: MediaServersView()
        case .about: AboutSettingsPane()
        }
    }
}

/// Sidebar row: white when focused, tinted when it is the category being shown.
struct SidebarCategoryStyle: ButtonStyle {
    let isSelected: Bool

    func makeBody(configuration: Configuration) -> some View {
        Row(configuration: configuration, isSelected: isSelected)
    }

    private struct Row: View {
        let configuration: ButtonStyle.Configuration
        let isSelected: Bool
        @Environment(\.isFocused) private var isFocused

        var body: some View {
            configuration.label
                .foregroundStyle(isFocused ? Color.black : Color.white)
                .padding(.horizontal, 24)
                .padding(.vertical, 18)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(
                    RoundedRectangle(cornerRadius: 18, style: .continuous)
                        .fill(isFocused ? Color.white : (isSelected ? Color.white.opacity(0.14) : Color.clear))
                )
                .scaleEffect(configuration.isPressed ? 0.98 : (isFocused ? 1.04 : 1))
                .animation(.easeOut(duration: 0.12), value: isFocused)
        }
    }
}

private let languageOptions: [(String, String)] = [
    ("", "Default"), ("en", "English"), ("es", "Spanish"), ("fr", "French"), ("de", "German"),
    ("it", "Italian"), ("pt", "Portuguese"), ("ru", "Russian"), ("ja", "Japanese"), ("ko", "Korean"),
    ("zh", "Chinese"), ("ar", "Arabic"), ("hi", "Hindi"), ("tr", "Turkish"), ("pl", "Polish"),
    ("nl", "Dutch"), ("sv", "Swedish"), ("no", "Norwegian"), ("da", "Danish"), ("fi", "Finnish"),
    ("el", "Greek"), ("he", "Hebrew"), ("cs", "Czech"), ("hu", "Hungarian"), ("ro", "Romanian"),
    ("uk", "Ukrainian"), ("vi", "Vietnamese"), ("th", "Thai"), ("id", "Indonesian"), ("hr", "Croatian"),
]

private let colorOptions: [(String, String)] = [
    ("#FFFFFF", "White"), ("#FFFF00", "Yellow"), ("#00FFFF", "Cyan"), ("#00FF00", "Green"),
    ("#FF00FF", "Magenta"), ("#BBBBBB", "Light grey"),
]

private let backgroundOptions: [(String, String)] = [
    ("#00000000", "None"), ("#80000000", "Translucent black"), ("#FF000000", "Black"),
]

private struct PlayerSettingsPane: View {
    @ObservedObject var binder: SettingsBinder

    var body: some View {
        let s = binder
        List {
            Section {
                Picker("Video engine", selection: s.bind({ $0.playerEngine }, { $0.playerEngine = $1 })) {
                    Text("mpv (MKV, ASS, all codecs)").tag(AppSettings.companion.ENGINE_MPV)
                    Text("Apple player (HLS/MP4)").tag(AppSettings.companion.ENGINE_AVPLAYER)
                }
                Toggle("Hardware decoding", isOn: s.bind({ $0.hardwareDecoding }, { $0.hardwareDecoding = $1 }))
            } header: {
                Text("Playback engine")
            } footer: {
                Text("mpv plays MKV, every FFmpeg codec and styled ASS/SSA subtitles. Apple's player only handles HLS and MP4.")
            }

            Section {
                Toggle("HDR / Dolby Vision passthrough", isOn: s.bind({ $0.hdrPassthrough }, { $0.hdrPassthrough = $1 }))
            } header: {
                Text("Picture")
            } footer: {
                Text("Turn on Settings ▸ Video and Audio ▸ Match Content on the Apple TV to switch frame rate and dynamic range per title.")
            }

            Section("Audio") {
                Picker("Audio language", selection: s.bind({ $0.preferredAudioLanguage }, { $0.preferredAudioLanguage = $1 })) {
                    ForEach(languageOptions, id: \.0) { Text($0.1).tag($0.0) }
                }
                Picker("Second audio language", selection: s.bind({ $0.secondaryAudioLanguage }, { $0.secondaryAudioLanguage = $1 })) {
                    ForEach(languageOptions, id: \.0) { Text($0.1).tag($0.0) }
                }
                NumberRow(title: "Audio delay", unit: "ms", step: 50, range: -5000...5000,
                          value: s.bind({ Int($0.audioDelayMs) }, { $0.audioDelayMs = Int32($1) }))
            }

            Section("Controls & buffering") {
                NumberRow(title: "Seek step", unit: "s", step: 5, range: 5...60,
                          value: s.bind({ Int($0.seekStepSeconds) }, { $0.seekStepSeconds = Int32($1) }))
                NumberRow(title: "Buffer", unit: "s", step: 30, range: 10...600,
                          value: s.bind({ Int($0.cacheSeconds) }, { $0.cacheSeconds = Int32($1) }))
                Toggle("Skip intro / recap / credits button", isOn: s.bind({ $0.skipIntroEnabled }, { $0.skipIntroEnabled = $1 }))
            }

            Section {
                Toggle("Auto-play next episode", isOn: s.bind({ $0.autoPlayNextEpisode }, { $0.autoPlayNextEpisode = $1 }))
                NumberRow(title: "Show “Up next” before end", unit: "s", step: 5, range: 5...180,
                          value: s.bind({ Int($0.nextEpisodePromptSeconds) }, { $0.nextEpisodePromptSeconds = Int32($1) }))
                TextField("Auto-play stream regex (e.g. 2160p|REMUX)",
                          text: s.bind({ $0.streamAutoPlayRegex }, { $0.streamAutoPlayRegex = $1 }))
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
            } header: {
                Text("Auto-play")
            } footer: {
                Text("With a regex set, the first stream whose name matches starts on its own.")
            }
        }
        .padding(.horizontal, 30)
    }
}

private struct SubtitleSettingsPane: View {
    @ObservedObject var binder: SettingsBinder

    var body: some View {
        let s = binder
        List {
            Section {
                Toggle("Show subtitles", isOn: s.bind({ $0.subtitlesEnabled }, { $0.subtitlesEnabled = $1 }))
                Picker("Language", selection: s.bind({ $0.subtitleLanguage }, { $0.subtitleLanguage = $1 })) {
                    ForEach(languageOptions, id: \.0) { Text($0.1).tag($0.0) }
                }
                Picker("Second language", selection: s.bind({ $0.secondarySubtitleLanguage }, { $0.secondarySubtitleLanguage = $1 })) {
                    ForEach(languageOptions, id: \.0) { Text($0.1).tag($0.0) }
                }
                Toggle("Forced subtitles", isOn: s.bind({ $0.useForcedSubtitles }, { $0.useForcedSubtitles = $1 }))
                Toggle("Add-on subtitles (OpenSubtitles…)", isOn: s.bind({ $0.loadAddonSubtitles }, { $0.loadAddonSubtitles = $1 }))
            } header: {
                Text("Languages")
            } footer: {
                Text("Add-on subtitles in your languages are listed first in the player's Subtitles menu.")
            }

            Section("Style") {
                NumberRow(title: "Size", unit: "%", step: 10, range: 50...200,
                          value: s.bind({ Int($0.subtitleSize) }, { $0.subtitleSize = Int32($1) }))
                NumberRow(title: "Position from bottom", unit: "%", step: 1, range: 0...50,
                          value: s.bind({ Int($0.subtitleVerticalOffset) }, { $0.subtitleVerticalOffset = Int32($1) }))
                Toggle("Bold", isOn: s.bind({ $0.subtitleBold }, { $0.subtitleBold = $1 }))
                Picker("Text colour", selection: s.bind({ $0.subtitleTextColor }, { $0.subtitleTextColor = $1 })) {
                    ForEach(colorOptions, id: \.0) { Text($0.1).tag($0.0) }
                }
                NumberRow(title: "Outline", unit: "", step: 1, range: 0...5,
                          value: s.bind({ Int($0.subtitleOutlineWidth) }, { $0.subtitleOutlineWidth = Int32($1) }))
                Picker("Background", selection: s.bind({ $0.subtitleBackgroundColor }, { $0.subtitleBackgroundColor = $1 })) {
                    ForEach(backgroundOptions, id: \.0) { Text($0.1).tag($0.0) }
                }
            }

            Section {
                Toggle("Restyle ASS/SSA subtitles", isOn: s.bind({ $0.overrideAssStyle }, { $0.overrideAssStyle = $1 }))
                NumberRow(title: "Subtitle delay", unit: "ms", step: 100, range: -10000...10000,
                          value: s.bind({ Int($0.subtitleDelayMs) }, { $0.subtitleDelayMs = Int32($1) }))
            } header: {
                Text("Advanced")
            } footer: {
                Text("Leave restyling off to keep anime typesetting exactly as authored (rendered by libass).")
            }
        }
        .padding(.horizontal, 30)
    }
}

private struct HomeSettingsPane: View {
    @ObservedObject var binder: SettingsBinder
    @EnvironmentObject private var app: AppModel

    var body: some View {
        let s = binder
        List {
            Section {
                Picker("Layout", selection: s.bind({ $0.homeLayout }, { $0.homeLayout = $1; app.objectWillChange.send() })) {
                    Text("Modern").tag(AppSettings.companion.LAYOUT_MODERN)
                    Text("Classic").tag(AppSettings.companion.LAYOUT_CLASSIC)
                    Text("Grid").tag(AppSettings.companion.LAYOUT_GRID)
                }
                Toggle("Hero carousel (Classic / Grid)", isOn: s.bind({ $0.showHero }, { $0.showHero = $1; app.objectWillChange.send() }))
            } header: {
                Text("Layout")
            } footer: {
                Text("Modern: artwork follows focus with landscape rows. Classic: hero carousel and poster rows. Grid: every catalog as a poster grid.")
            }

            Section("Rows") {
                Toggle("Continue Watching row", isOn: s.bind({ $0.showContinueWatching }, { $0.showContinueWatching = $1 }))
                NavigationLink("Catalogs and hero sources") { HomeCatalogsSettings(binder: binder) }
            }
        }
        .padding(.horizontal, 30)
    }
}

private struct MetadataSettingsPane: View {
    @ObservedObject var binder: SettingsBinder
    @EnvironmentObject private var app: AppModel

    var body: some View {
        let s = binder
        List {
            Section {
                Toggle("TMDB enrichment", isOn: s.bind({ $0.tmdbEnrichment }, { $0.tmdbEnrichment = $1 }))
                    .disabled(!app.core.config.hasTmdb)
                TextField("TMDB language (en-US)", text: s.bind({ $0.tmdbLanguage }, { $0.tmdbLanguage = $1 }))
                    .autocorrectionDisabled()
            } header: {
                Text("TMDB")
            } footer: {
                Text(app.core.config.hasTmdb
                     ? "Off: details use the add-on's own metadata. On: the groups below choose what TMDB replaces."
                     : "This build has no TMDB key.")
            }

            if binder.settings.tmdbEnrichment {
                Section {
                    Toggle("Artwork (logo, backdrop)", isOn: s.bind({ $0.tmdbUseArtwork }, { $0.tmdbUseArtwork = $1 }))
                    Toggle("Basic info (title, description, genres, rating)", isOn: s.bind({ $0.tmdbUseBasicInfo }, { $0.tmdbUseBasicInfo = $1 }))
                    Toggle("Details (runtime, status, age rating, country)", isOn: s.bind({ $0.tmdbUseDetails }, { $0.tmdbUseDetails = $1 }))
                    Toggle("Credits (cast photos, director, writer)", isOn: s.bind({ $0.tmdbUseCredits }, { $0.tmdbUseCredits = $1 }))
                    Toggle("Episodes (titles, overviews, thumbnails)", isOn: s.bind({ $0.tmdbUseEpisodes }, { $0.tmdbUseEpisodes = $1 }))
                    Toggle("Production companies", isOn: s.bind({ $0.tmdbUseProductions }, { $0.tmdbUseProductions = $1 }))
                    Toggle("Networks", isOn: s.bind({ $0.tmdbUseNetworks }, { $0.tmdbUseNetworks = $1 }))
                    Toggle("Trailers", isOn: s.bind({ $0.tmdbUseTrailers }, { $0.tmdbUseTrailers = $1 }))
                    Toggle("More like this", isOn: s.bind({ $0.tmdbUseMoreLikeThis }, { $0.tmdbUseMoreLikeThis = $1 }))
                    Toggle("Collections", isOn: s.bind({ $0.tmdbUseCollections }, { $0.tmdbUseCollections = $1 }))
                } header: {
                    Text("Use TMDB for")
                } footer: {
                    Text("Groups switched off keep the add-on's own data.")
                }
            }

            Section {
                Toggle("MDBList ratings", isOn: s.bind({ $0.showMdbListRatings }, { $0.showMdbListRatings = $1 }))
                Toggle("Ratings on the Home hero", isOn: s.bind({ $0.showMdbListOnHero }, { $0.showMdbListOnHero = $1 }))
                TextField("MDBList API key", text: s.bind({ $0.mdbListApiKey }, { $0.mdbListApiKey = $1 }))
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
            } header: {
                Text("Ratings")
            } footer: {
                Text("IMDb, Rotten Tomatoes, Metacritic, Letterboxd and more on the details page.")
            }
        }
        .padding(.horizontal, 30)
    }
}

private struct AboutSettingsPane: View {
    var body: some View {
        List {
            Section("Nuvio for Apple TV") {
                LabeledContent("Version", value: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "")
                LabeledContent("Build", value: Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "")
            }
            Section("Built with") {
                LabeledContent("Player", value: "mpv via MPVKit")
                LabeledContent("Subtitles", value: "libass")
                LabeledContent("Decoding", value: "FFmpeg, VideoToolbox")
                LabeledContent("Shared core", value: "Kotlin Multiplatform")
            }
        }
        .padding(.horizontal, 30)
    }
}

/// tvOS has no Stepper; a row with − / + buttons instead.
struct NumberRow: View {
    let title: String
    let unit: String
    let step: Int
    let range: ClosedRange<Int>
    @Binding var value: Int

    var body: some View {
        HStack {
            Text(title)
            Spacer()
            Button { value = max(range.lowerBound, value - step) } label: { Image(systemName: "minus") }
            Text("\(value)\(unit.isEmpty ? "" : " " + unit)")
                .monospacedDigit()
                .frame(minWidth: 160)
            Button { value = min(range.upperBound, value + step) } label: { Image(systemName: "plus") }
        }
    }
}

private struct HomeCatalogsSettings: View {
    @ObservedObject var binder: SettingsBinder
    @EnvironmentObject private var app: AppModel

    var body: some View {
        List {
            Section("Shown on Home") {
            ForEach(app.core.allHomeSlots(), id: \.key) { slot in
                Toggle(isOn: Binding(
                    get: { !binder.settings.isCatalogHidden(key: slot.key) },
                    set: { visible in
                        binder.settings.setCatalogHidden(key: slot.key, hidden: !visible)
                        binder.objectWillChange.send()
                        app.objectWillChange.send()
                    }
                )) {
                    VStack(alignment: .leading) {
                        Text(slot.title)
                        Text(slot.addon.displayName).font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
            }
            Section("Hero sources (none selected = all rows)") {
                ForEach(app.core.allHomeSlots(), id: \.key) { slot in
                    Toggle(slot.title, isOn: Binding(
                        get: { binder.settings.isHeroCatalog(key: slot.key) },
                        set: { on in
                            binder.settings.setHeroCatalog(key: slot.key, enabled: on)
                            binder.objectWillChange.send()
                            app.objectWillChange.send()
                        }
                    ))
                }
            }
        }
        .navigationTitle("Catalogs on Home")
    }
}
