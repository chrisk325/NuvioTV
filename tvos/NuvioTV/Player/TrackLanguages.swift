import Foundation

/// Audio / subtitle language preferences → codes the players match on.
/// Mirrors Android resolvePreferredAudioLanguages (AudioLanguageOption DEFAULT / DEVICE / ORIGINAL).
enum TrackLanguages {
    static let fileDefault = ""
    static let device = "device"
    static let original = "original"

    /// Ordered 2-letter codes to prefer for audio. Empty = keep the file's default track.
    static func audio(preferred: String, secondary: String, originalLanguage: String?) -> [String] {
        var codes: [String] = []
        switch preferred {
        case fileDefault:
            break
        case device:
            codes += Locale.preferredLanguages.compactMap { normalize($0) }
        case original:
            if let original = normalize(originalLanguage) { codes.append(original) }
        default:
            if let code = normalize(preferred) { codes.append(code) }
        }
        if let second = normalize(secondary), ![fileDefault, device, original].contains(secondary) {
            codes.append(second)
        }
        var seen = Set<String>()
        return codes.filter { seen.insert($0).inserted }
    }

    /// Comma list for mpv's alang/slang. Tracks are usually tagged with ISO 639-2 ("eng",
    /// "ger"/"deu"), so each 2-letter code is sent with its 3-letter forms too.
    static func mpvList(_ codes: [String]) -> String {
        var seen = Set<String>()
        return codes.flatMap { [$0] + (iso639_2[$0] ?? []) }
            .filter { seen.insert($0).inserted }
            .joined(separator: ",")
    }

    /// "en", "eng", "en-US", "English" → "en".
    static func normalize(_ raw: String?) -> String? {
        guard var value = raw?.trimmingCharacters(in: .whitespaces).lowercased(), !value.isEmpty else { return nil }
        if [fileDefault, device, original].contains(value) { return nil }
        value = String(value.split(whereSeparator: { $0 == "-" || $0 == "_" }).first ?? Substring(value))
        if value.count == 2 { return value }
        if let match = iso639_2.first(where: { $0.value.contains(value) }) { return match.key }
        if let match = englishNames.first(where: { $0.value == value }) { return match.key }
        return nil
    }

    private static let iso639_2: [String: [String]] = [
        "en": ["eng"], "es": ["spa"], "fr": ["fre", "fra"], "de": ["ger", "deu"], "it": ["ita"],
        "pt": ["por"], "ru": ["rus"], "ja": ["jpn"], "ko": ["kor"], "zh": ["chi", "zho"],
        "ar": ["ara"], "hi": ["hin"], "tr": ["tur"], "pl": ["pol"], "nl": ["dut", "nld"],
        "sv": ["swe"], "no": ["nor", "nob", "nno"], "da": ["dan"], "fi": ["fin"], "el": ["gre", "ell"],
        "he": ["heb"], "cs": ["cze", "ces"], "hu": ["hun"], "ro": ["rum", "ron"], "uk": ["ukr"],
        "vi": ["vie"], "th": ["tha"], "id": ["ind"], "hr": ["hrv"], "ta": ["tam"], "te": ["tel"],
        "ml": ["mal"], "bn": ["ben"], "fa": ["per", "fas"], "ms": ["may", "msa"], "bg": ["bul"],
        "sr": ["srp"], "sk": ["slo", "slk"], "sl": ["slv"], "lt": ["lit"], "lv": ["lav"], "et": ["est"],
    ]

    private static let englishNames: [String: String] = [
        "en": "english", "es": "spanish", "fr": "french", "de": "german", "it": "italian",
        "pt": "portuguese", "ru": "russian", "ja": "japanese", "ko": "korean", "zh": "chinese",
        "ar": "arabic", "hi": "hindi", "tr": "turkish", "pl": "polish", "nl": "dutch",
        "sv": "swedish", "no": "norwegian", "da": "danish", "fi": "finnish", "el": "greek",
        "he": "hebrew", "cs": "czech", "hu": "hungarian", "ro": "romanian", "uk": "ukrainian",
        "vi": "vietnamese", "th": "thai", "id": "indonesian", "hr": "croatian", "ta": "tamil",
        "te": "telugu", "ml": "malayalam", "bn": "bengali", "fa": "persian",
    ]
}
