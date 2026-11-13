import SwiftUI

struct RootView: View {
    var body: some View {
        TabView {
            NavigationStack { HomeView().nuvioDestinations() }
                .tabItem { Label("Home", systemImage: "house") }
            NavigationStack { SearchView().nuvioDestinations() }
                .tabItem { Label("Search", systemImage: "magnifyingglass") }
            NavigationStack { LibraryView().nuvioDestinations() }
                .tabItem { Label("Library", systemImage: "bookmark") }
            NavigationStack { AddonsView() }
                .tabItem { Label("Add-ons", systemImage: "puzzlepiece.extension") }
            NavigationStack { SettingsView() }
                .tabItem { Label("Settings", systemImage: "gearshape") }
        }
    }
}
