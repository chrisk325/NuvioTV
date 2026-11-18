import Foundation

/// What kind of subtitle was picked for a series / movie ("English SDH", "English forced",
/// "Off"), so the same kind can be picked again on its other episodes and next time.
struct SubtitleChoice: Codable, Equatable {
    var off: Bool
    var lang: String?
    var forced: Bool
    var sdh: Bool
    var external: Bool
    /// Normalised track name (without the language), to prefer the very same source / variant.
    var name: String?

    init(track: MPVTrack?) {
        guard let track else {
            off = true; lang = nil; forced = false; sdh = false; external = false; name = nil
            return
        }
        off = false
        lang = track.lang.map(SubtitleLanguages.normalize).flatMap { $0.isEmpty ? nil : $0 }
        forced = Self.isForced(track)
        sdh = Self.isSDH(track)
        external = track.isExternal
        name = Self.normalizedName(track)
    }

    static func decode(_ raw: String) -> SubtitleChoice? {
        guard let data = raw.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(SubtitleChoice.self, from: data)
    }

    var encoded: String? {
        (try? JSONEncoder().encode(self)).flatMap { String(data: $0, encoding: .utf8) }
    }

    /// Score of [track] as a match, nil when it can't stand in: other language, or forced-ness
    /// differs (forced subs only cover foreign dialogue, so never swap one kind for the other).
    /// SDH and the source are preferences only.
    func score(_ track: MPVTrack) -> Int? {
        let trackLang = track.lang.map(SubtitleLanguages.normalize).flatMap { $0.isEmpty ? nil : $0 }
        if let lang, let trackLang, lang != trackLang { return nil }
        if (lang == nil) != (trackLang == nil) && name != Self.normalizedName(track) { return nil }
        if forced != Self.isForced(track) { return nil }
        var score = 100
        score += sdh == Self.isSDH(track) ? 30 : -30
        score += external == track.isExternal ? 10 : 0
        if let name, name == Self.normalizedName(track) { score += 15 }
        return score
    }

    /// Every attribute matches (language, forced, SDH, source, and the name when there was one).
    var perfectScore: Int { 140 + (name == nil ? 0 : 15) }

    // MARK: Track traits

    static func isForced(_ track: MPVTrack) -> Bool {
        track.isForced || words(track).contains("forced")
    }

    /// SDH / hearing-impaired / closed captions, from the track's name.
    static func isSDH(_ track: MPVTrack) -> Bool {
        let tokens = words(track)
        if tokens.contains("sdh") || tokens.contains("cc") || tokens.contains("hoh") || tokens.contains("hi") { return true }
        let text = (track.title ?? "").lowercased()
        return text.contains("hearing") || text.contains("deaf") || text.contains("closed caption")
    }

    private static func words(_ track: MPVTrack) -> Set<String> {
        let text = (track.title ?? "").lowercased()
        return Set(text.split(whereSeparator: { !$0.isLetter && !$0.isNumber }).map(String.init))
    }

    /// The track name without its language part ("English · OpenSubtitles v3" → "opensubtitles v3").
    static func normalizedName(_ track: MPVTrack) -> String? {
        guard var text = track.title?.lowercased().trimmingCharacters(in: .whitespaces), !text.isEmpty else { return nil }
        if let lang = track.lang {
            let language = SubtitleLanguages.name(lang).lowercased()
            if text.hasPrefix(language) { text = String(text.dropFirst(language.count)) }
        }
        let cleaned = text.trimmingCharacters(in: CharacterSet(charactersIn: " ·-–—:|()[]"))
        return cleaned.isEmpty ? nil : cleaned
    }
}
