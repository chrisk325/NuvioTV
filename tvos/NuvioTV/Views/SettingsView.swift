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

struct SettingsView: View {
    @EnvironmentObject private var app: AppModel

    var body: some View {
        SettingsForm(binder: SettingsBinder(app.settings))
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

private struct SettingsForm: View {
    @ObservedObject var binder: SettingsBinder
    @EnvironmentObject private var app: AppModel

    var body: some View {
        let s = binder
        List {
            Section("Player") {
                Picker("Video engine", selection: s.bind({ $0.playerEngine }, { $0.playerEngine = $1 })) {
                    Text("mpv (MKV, ASS, all codecs)").tag(AppSettings.companion.ENGINE_MPV)
                    Text("Apple player (HLS/MP4)").tag(AppSettings.companion.ENGINE_AVPLAYER)
                }
                Toggle("Hardware decoding", isOn: s.bind({ $0.hardwareDecoding }, { $0.hardwareDecoding = $1 }))
                Toggle("HDR / Dolby Vision passthrough", isOn: s.bind({ $0.hdrPassthrough }, { $0.hdrPassthrough = $1 }))
                NumberRow(title: "Buffer", unit: "s", step: 30, range: 10...600,
                          value: s.bind({ Int($0.cacheSeconds) }, { $0.cacheSeconds = Int32($1) }))
                NumberRow(title: "Seek step", unit: "s", step: 5, range: 5...60,
                          value: s.bind({ Int($0.seekStepSeconds) }, { $0.seekStepSeconds = Int32($1) }))
                Picker("Audio language", selection: s.bind({ $0.preferredAudioLanguage }, { $0.preferredAudioLanguage = $1 })) {
                    ForEach(languageOptions, id: \.0) { Text($0.1).tag($0.0) }
                }
                Picker("Second audio language", selection: s.bind({ $0.secondaryAudioLanguage }, { $0.secondaryAudioLanguage = $1 })) {
                    ForEach(languageOptions, id: \.0) { Text($0.1).tag($0.0) }
                }
                NumberRow(title: "Audio delay", unit: "ms", step: 50, range: -5000...5000,
                          value: s.bind({ Int($0.audioDelayMs) }, { $0.audioDelayMs = Int32($1) }))
                Toggle("Auto-play next episode", isOn: s.bind({ $0.autoPlayNextEpisode }, { $0.autoPlayNextEpisode = $1 }))
                NumberRow(title: "Show “Up next” before end", unit: "s", step: 5, range: 5...180,
                          value: s.bind({ Int($0.nextEpisodePromptSeconds) }, { $0.nextEpisodePromptSeconds = Int32($1) }))
                TextField("Auto-play stream regex (e.g. 2160p|REMUX)",
                          text: s.bind({ $0.streamAutoPlayRegex }, { $0.streamAutoPlayRegex = $1 }))
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
            }

            Section("Subtitles") {
                Toggle("Show subtitles", isOn: s.bind({ $0.subtitlesEnabled }, { $0.subtitlesEnabled = $1 }))
                Picker("Language", selection: s.bind({ $0.subtitleLanguage }, { $0.subtitleLanguage = $1 })) {
                    ForEach(languageOptions, id: \.0) { Text($0.1).tag($0.0) }
                }
                Picker("Second language", selection: s.bind({ $0.secondarySubtitleLanguage }, { $0.secondarySubtitleLanguage = $1 })) {
                    ForEach(languageOptions, id: \.0) { Text($0.1).tag($0.0) }
                }
                Toggle("Forced subtitles", isOn: s.bind({ $0.useForcedSubtitles }, { $0.useForcedSubtitles = $1 }))
                Toggle("Add-on subtitles (OpenSubtitles…)", isOn: s.bind({ $0.loadAddonSubtitles }, { $0.loadAddonSubtitles = $1 }))
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
                Toggle("Restyle ASS/SSA subtitles", isOn: s.bind({ $0.overrideAssStyle }, { $0.overrideAssStyle = $1 }))
                NumberRow(title: "Subtitle delay", unit: "ms", step: 100, range: -10000...10000,
                          value: s.bind({ Int($0.subtitleDelayMs) }, { $0.subtitleDelayMs = Int32($1) }))
            }

            Section("Profile") {
                NavigationLink("Profiles (\(app.currentProfile?.name ?? "Main"))") { ProfilesSettingsView() }
            }

            Section("Accounts & services") {
                NavigationLink("Nuvio account, Trakt, Simkl & AniList") { AccountsView() }
                NavigationLink("Debrid (Torbox, Premiumize, Real-Debrid)") { DebridSettingsView() }
                NavigationLink("Media servers (Jellyfin, Emby)") { MediaServersView() }
            }

            Section("Home") {
                Toggle("Continue Watching row", isOn: s.bind({ $0.showContinueWatching }, { $0.showContinueWatching = $1 }))
                NavigationLink("Catalogs on Home") { HomeCatalogsSettings(binder: binder) }
            }

            Section("Metadata") {
                Toggle("TMDB enrichment", isOn: s.bind({ $0.tmdbEnrichment }, { $0.tmdbEnrichment = $1 }))
                    .disabled(!app.core.config.hasTmdb)
                if !app.core.config.hasTmdb {
                    Text("No TMDB key in this build.").font(.caption).foregroundStyle(.secondary)
                }
                TextField("TMDB language (en-US)", text: s.bind({ $0.tmdbLanguage }, { $0.tmdbLanguage = $1 }))
                    .autocorrectionDisabled()
                Toggle("MDBList ratings", isOn: s.bind({ $0.showMdbListRatings }, { $0.showMdbListRatings = $1 }))
                TextField("MDBList API key", text: s.bind({ $0.mdbListApiKey }, { $0.mdbListApiKey = $1 }))
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
            }

            Section("About") {
                LabeledContent("Version", value: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "")
                LabeledContent("Player", value: "mpv via MPVKit (libass, FFmpeg, libplacebo)")
            }
        }
        .navigationTitle("Settings")
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
        .navigationTitle("Catalogs on Home")
    }
}
