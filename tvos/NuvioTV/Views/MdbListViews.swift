import SwiftUI
import NuvioShared

private let mdbListWatchlistKey = "mdblist:watchlist"

/// MDBList lists in the Library (Android LibraryScreen with an MDBList source): a tab per list
/// (watchlist, own static and dynamic lists, external and liked lists), the list's titles in the
/// chosen order, and creating / renaming / deleting own lists.
struct MdbListLibrarySection: View {
    @EnvironmentObject private var app: AppModel
    let columns: [GridItem]

    @State private var lists: [MdbListLibraryList] = []
    @State private var hidden: Set<String> = []
    @AppStorage("mdblist.library.list") private var selectedKey = mdbListWatchlistKey
    @AppStorage("mdblist.library.sort") private var sortName = "DEFAULT"
    @State private var items: [MetaPreview] = []
    @State private var isLoading = false
    @State private var error: String?
    @State private var showSort = false
    @State private var editor: MdbListEditor?
    @State private var confirmDelete = false
    @State private var reload = 0

    private var tracker: MdbListTracker { app.core.mdbListTracker }
    private var visibleLists: [MdbListLibraryList] { lists.filter { !hidden.contains($0.key) } }
    private var current: MdbListLibraryList? {
        visibleLists.first { $0.key == selectedKey } ?? visibleLists.first
    }
    private var sort: MdbListSort { tracker.sort(named: sortName) }

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text("MDBList").font(.headline).padding(.horizontal, 60)
            if !visibleLists.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 12) {
                        ForEach(visibleLists, id: \.key) { list in
                            Button { selectedKey = list.key } label: {
                                Text(list.name).lineLimit(1)
                            }
                            .buttonStyle(SeasonTabStyle(isSelected: list.key == current?.key))
                        }
                    }
                    .padding(.horizontal, 60)
                    .padding(.vertical, 16)
                }
                .focusSection()
            }
            HStack(spacing: 24) {
                Button { showSort = true } label: {
                    Label(sort.label, systemImage: "arrow.up.arrow.down")
                }
                Button { editor = MdbListEditor(key: nil, name: "", isPrivate: true) } label: {
                    Label("New list", systemImage: "plus")
                }
                if let current, current.isEditable {
                    Button { editor = MdbListEditor(key: current.key, name: current.name, isPrivate: current.isPrivate) } label: {
                        Label("Edit list", systemImage: "pencil")
                    }
                    Button(role: .destructive) { confirmDelete = true } label: {
                        Label("Delete list", systemImage: "trash")
                    }
                }
                if let current {
                    Text(kindLabel(current)).font(.callout).foregroundStyle(.secondary)
                }
            }
            .padding(.horizontal, 60)
            .focusSection()

            Group {
                if isLoading && items.isEmpty {
                    ProgressView().frame(maxWidth: .infinity).padding(40)
                } else if let error {
                    Text(error).foregroundStyle(.secondary).padding(.horizontal, 60)
                } else if items.isEmpty {
                    Text("This list is empty.").foregroundStyle(.secondary).padding(.horizontal, 60)
                } else {
                    LazyVGrid(columns: columns, spacing: 50) {
                        ForEach(items, id: \.id) { item in
                            PosterCard(item: item)
                        }
                    }
                    .padding(.horizontal, 60)
                }
            }
        }
        .task(id: reload) { await loadLists() }
        .task(id: "\(current?.key ?? "")|\(sortName)|\(reload)") { await loadItems() }
        .onAppear { reload += 1 }
        .confirmationDialog("Sort by", isPresented: $showSort, titleVisibility: .visible) {
            ForEach(tracker.sorts, id: \.name) { option in
                Button(option.label) { sortName = option.name }
            }
        }
        .confirmationDialog("Delete \(current?.name ?? "this list")?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Delete", role: .destructive) {
                guard let key = current?.key else { return }
                Task { @MainActor in
                    do {
                        try await tracker.deleteList(key: key)
                        selectedKey = mdbListWatchlistKey
                        reload += 1
                    } catch {
                        self.error = error.localizedDescription
                    }
                }
            }
        }
        .sheet(item: $editor) { value in
            MdbListEditorSheet(editor: value) { saved in
                editor = nil
                if saved {
                    tracker.clearListCache(key: nil)
                    reload += 1
                }
            }
        }
    }

    private func kindLabel(_ list: MdbListLibraryList) -> String {
        switch list.kind {
        case "watchlist": return "Watchlist"
        case "personal": return list.isPrivate ? "Private list" : "Public list"
        case "dynamic": return "Dynamic list (read-only)"
        case "external": return "External list (read-only)"
        default: return "Liked list (read-only)"
        }
    }

    @MainActor
    private func loadLists() async {
        hidden = tracker.hiddenListKeys
        do {
            lists = try await tracker.lists()
        } catch {
            if lists.isEmpty { self.error = error.localizedDescription }
        }
    }

    @MainActor
    private func loadItems() async {
        guard let key = current?.key ?? (lists.isEmpty ? mdbListWatchlistKey : nil) else { return }
        isLoading = true
        do {
            let loaded = try await tracker.listItems(key: key, sort: sort)
            guard !Task.isCancelled else { return }
            items = loaded
            error = nil
        } catch {
            if !Task.isCancelled { self.error = error.localizedDescription }
        }
        isLoading = false
    }
}

struct MdbListEditor: Identifiable {
    /// nil: a new list.
    let key: String?
    var name: String
    var isPrivate: Bool
    var id: String { key ?? "new" }
}

/// New list / rename and privacy of an own static list.
struct MdbListEditorSheet: View {
    @EnvironmentObject private var app: AppModel
    @State var editor: MdbListEditor
    let onDone: (Bool) -> Void
    @State private var error: String?
    @State private var isSaving = false

    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            Text(editor.key == nil ? "New MDBList list" : "Edit list").font(.title2).bold()
            TextField("Name", text: $editor.name)
            Toggle("Private", isOn: $editor.isPrivate)
            if let error {
                Text(error).foregroundStyle(.red)
            }
            HStack(spacing: 30) {
                Button(isSaving ? "Saving…" : "Save") {
                    Task { @MainActor in
                        isSaving = true
                        do {
                            let tracker = app.core.mdbListTracker
                            if let key = editor.key {
                                try await tracker.updateList(key: key, name: editor.name, isPrivate: editor.isPrivate)
                            } else {
                                try await tracker.createList(name: editor.name, isPrivate: editor.isPrivate)
                            }
                            isSaving = false
                            onDone(true)
                        } catch {
                            isSaving = false
                            self.error = error.localizedDescription
                        }
                    }
                }
                .disabled(editor.name.trimmingCharacters(in: .whitespaces).isEmpty || isSaving)
                Button("Cancel", role: .cancel) { onDone(false) }
            }
        }
        .padding(80)
        .frame(maxWidth: 1200)
    }
}

/// Details page "Lists": the title's membership in the watchlist and own static lists.
struct MdbListMembershipSheet: View {
    @EnvironmentObject private var app: AppModel
    let meta: Meta
    let onDone: () -> Void
    @State private var lists: [MdbListLibraryList] = []
    @State private var member: Set<String> = []
    @State private var isLoading = true
    @State private var busy: String?
    @State private var error: String?
    @State private var editor: MdbListEditor?

    private var tracker: MdbListTracker { app.core.mdbListTracker }
    private var imdbId: String? {
        (meta.imdbId ?? (meta.id.hasPrefix("tt") ? meta.id : nil))?.components(separatedBy: ":").first
    }
    private var tmdbId: KotlinInt? {
        guard meta.id.hasPrefix("tmdb:") else { return nil }
        let parts = meta.id.split(separator: ":")
        guard parts.count > 1, let value = Int32(parts[1]) else { return nil }
        return KotlinInt(int: value)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            Text("MDBList lists").font(.title2).bold()
            Text(meta.name).foregroundStyle(.secondary)
            if isLoading {
                ProgressView()
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        ForEach(lists, id: \.key) { list in
                            Button {
                                toggle(list)
                            } label: {
                                HStack(spacing: 20) {
                                    Image(systemName: member.contains(list.key) ? "checkmark.circle.fill" : "circle")
                                    Text(list.name)
                                    Spacer()
                                    if busy == list.key { ProgressView() }
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                            }
                            .disabled(busy != nil)
                        }
                    }
                    .padding(.vertical, 10)
                }
                .frame(maxHeight: 600)
            }
            if let error {
                Text(error).foregroundStyle(.red)
            }
            HStack(spacing: 30) {
                Button("Done") { onDone() }
                Button { editor = MdbListEditor(key: nil, name: "", isPrivate: true) } label: {
                    Label("New list", systemImage: "plus")
                }
            }
        }
        .padding(80)
        .frame(maxWidth: 1200)
        .task { await load() }
        .sheet(item: $editor) { value in
            MdbListEditorSheet(editor: value) { saved in
                editor = nil
                if saved { Task { await load() } }
            }
        }
    }

    @MainActor
    private func load() async {
        isLoading = true
        do {
            let all = try await tracker.lists().filter { $0.accepts(isShow: meta.isSeriesLike) }
            lists = all
            member = try await tracker.listsContaining(imdbId: imdbId, tmdbId: tmdbId, lists: all)
            error = nil
        } catch {
            self.error = error.localizedDescription
        }
        isLoading = false
    }

    private func toggle(_ list: MdbListLibraryList) {
        let adding = !member.contains(list.key)
        busy = list.key
        Task { @MainActor in
            do {
                try await tracker.setInList(key: list.key, imdbId: imdbId, tmdbId: tmdbId, isShow: meta.isSeriesLike, add: adding)
                if adding { member.insert(list.key) } else { member.remove(list.key) }
                error = nil
            } catch {
                self.error = error.localizedDescription
            }
            busy = nil
        }
    }
}

/// Settings → Accounts → MDBList: which lists show in the Library (the watchlist always does).
struct MdbListVisibilityView: View {
    @EnvironmentObject private var app: AppModel
    @State private var lists: [MdbListLibraryList] = []
    @State private var hidden: Set<String> = []
    @State private var error: String?

    var body: some View {
        List {
            if let error {
                Text(error).foregroundStyle(.secondary)
            }
            ForEach(lists.filter { $0.kind != "watchlist" }, id: \.key) { list in
                Button {
                    let hide = !hidden.contains(list.key)
                    app.core.mdbListTracker.setListHidden(key: list.key, hidden: hide)
                    hidden = app.core.mdbListTracker.hiddenListKeys
                } label: {
                    HStack {
                        Image(systemName: hidden.contains(list.key) ? "eye.slash" : "eye")
                        Text(list.name)
                        Spacer()
                        Text(list.kind.capitalized).foregroundStyle(.secondary)
                    }
                }
            }
        }
        .navigationTitle("MDBList lists")
        .task {
            hidden = app.core.mdbListTracker.hiddenListKeys
            do {
                lists = try await app.core.mdbListTracker.lists()
            } catch {
                self.error = error.localizedDescription
            }
        }
    }
}
