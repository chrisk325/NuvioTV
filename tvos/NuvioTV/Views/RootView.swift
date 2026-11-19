import SwiftUI

struct RootView: View {
    @EnvironmentObject private var app: AppModel
    /// Tagged so toggling tabs in Settings > Appearance keeps you on Settings.
    @State private var selection = "home"

    var body: some View {
        // Search, Live TV, Library and Add-ons can be turned off in Settings > Appearance;
        // Home and Settings are always there.
        let settings = app.settings
        TabView(selection: $selection) {
            NavigationStack { HomeView().nuvioDestinations() }
                .tabItem { Label("Home", systemImage: "house") }
                .tag("home")
            if !settings.isTabHidden(tab: TopBarTab.search.rawValue) {
                NavigationStack { SearchView().nuvioDestinations() }
                    .tabItem { Label("Search", systemImage: "magnifyingglass") }
                    .tag(TopBarTab.search.rawValue)
            }
            if !settings.isTabHidden(tab: TopBarTab.discover.rawValue) {
                NavigationStack { DiscoverView().nuvioDestinations() }
                    .tabItem { Label("Discover", systemImage: "safari") }
                    .tag(TopBarTab.discover.rawValue)
            }
            if !settings.isTabHidden(tab: TopBarTab.liveTv.rawValue) {
                NavigationStack { LiveTvView() }
                    .tabItem { Label("Live TV", systemImage: "tv") }
                    .tag(TopBarTab.liveTv.rawValue)
            }
            if !settings.isTabHidden(tab: TopBarTab.library.rawValue) {
                NavigationStack { LibraryView().nuvioDestinations() }
                    .tabItem { Label("Library", systemImage: "bookmark") }
                    .tag(TopBarTab.library.rawValue)
            }
            if !settings.isTabHidden(tab: TopBarTab.addons.rawValue) {
                NavigationStack { AddonsView() }
                    .tabItem { Label("Add-ons", systemImage: "puzzlepiece.extension") }
                    .tag(TopBarTab.addons.rawValue)
            }
            NavigationStack { SettingsView() }
                .tabItem { Label("Settings", systemImage: "gearshape") }
                .tag("settings")
        }
    }
}

/// Top bar items that can be hidden (Settings > Appearance).
enum TopBarTab: String, CaseIterable, Identifiable {
    case search, discover, liveTv = "livetv", library, addons

    var id: String { rawValue }

    var title: String {
        switch self {
        case .search: return "Search"
        case .discover: return "Discover"
        case .liveTv: return "Live TV"
        case .library: return "Library"
        case .addons: return "Add-ons"
        }
    }

    var icon: String {
        switch self {
        case .search: return "magnifyingglass"
        case .discover: return "safari"
        case .liveTv: return "tv"
        case .library: return "bookmark"
        case .addons: return "puzzlepiece.extension"
        }
    }
}
