import Foundation
import NuvioShared

enum AppConfig {
    /// Reads the Nuvio* keys that Config/Base.xcconfig + Secrets.xcconfig put into Info.plist.
    static func load(bundle: Bundle = .main) -> NuvioConfig {
        func value(_ key: String) -> String {
            let raw = (bundle.object(forInfoDictionaryKey: key) as? String) ?? ""
            let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            // An unset build setting can leave the literal "$(NAME)" behind.
            return trimmed.hasPrefix("$(") ? "" : trimmed
        }
        return NuvioConfig(
            tmdbApiKey: value("NuvioTmdbApiKey"),
            traktClientId: value("NuvioTraktClientId"),
            traktClientSecret: value("NuvioTraktClientSecret"),
            simklClientId: value("NuvioSimklClientId"),
            simklAppName: value("NuvioSimklAppName").isEmpty ? "nuvio" : value("NuvioSimklAppName"),
            mdbListClientId: value("NuvioMdbListClientId"),
            aniListClientId: value("NuvioAniListClientId"),
            premiumizeClientId: value("NuvioPremiumizeClientId"),
            introDbApiUrl: value("NuvioIntroDbApiUrl"),
            trailerApiUrl: value("NuvioTrailerApiUrl"),
            supabaseUrl: value("NuvioSupabaseUrl"),
            supabaseAnonKey: value("NuvioSupabaseAnonKey")
        )
    }
}
