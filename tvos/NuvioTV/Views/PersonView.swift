import SwiftUI
import NuvioShared

/// Horizontal row of people (TMDB cast / crew); each opens the person page.
struct PeopleRow: View {
    let title: String
    let people: [TmdbPerson]
    /// Add-on cast without a TMDB id (or builds without a TMDB key) can't open a person page.
    var linksToTmdb = true
    /// Details page: the first avatar is the `.people` focus anchor.
    var focus: FocusState<DetailFocus?>.Binding? = nil
    /// Runs when Up is pressed on an avatar (set on each focused card: a handler on the row
    /// itself never receives the press).
    var onMoveUp: (() -> Void)? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(title).font(.headline).padding(.horizontal, 80)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 36) {
                    ForEach(Array(people.enumerated()), id: \.offset) { index, person in
                        VStack(spacing: 10) {
                            if linksToTmdb && person.id > 0 {
                                NavigationLink(value: Route.person(person.id)) { avatar(person) }
                                    .buttonStyle(.card)
                                    .clipShape(Circle())
                                    .detailFocus(focus, .people, when: index == 0)
                                    .onMoveUp(onMoveUp)
                            } else {
                                Button {} label: { avatar(person) }
                                    .buttonStyle(.card)
                                    .clipShape(Circle())
                                    .detailFocus(focus, .people, when: index == 0)
                                    .onMoveUp(onMoveUp)
                            }
                            Text(person.name).font(.caption).lineLimit(1).frame(width: 190)
                            if let role = person.role {
                                Text(role).font(.caption2).foregroundStyle(.secondary).lineLimit(1).frame(width: 190)
                            }
                        }
                    }
                }
                .padding(.horizontal, 80)
                .padding(.vertical, 24)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .focusSection()
    }

    private func avatar(_ person: TmdbPerson) -> some View {
        ZStack {
            Circle().fill(Color.white.opacity(0.1))
            if person.photo != nil {
                RemoteImage(url: person.photo)
            } else {
                Text(String(person.name.prefix(1))).font(.title)
            }
        }
        .frame(width: 170, height: 170)
        .clipShape(Circle())
    }
}

struct PersonView: View {
    @EnvironmentObject private var app: AppModel
    let personId: Int32

    @State private var person: TmdbPersonDetail?
    @State private var failed = false

    var body: some View {
        Group {
            if let person {
                ScrollView {
                    VStack(alignment: .leading, spacing: 40) {
                        HStack(alignment: .top, spacing: 60) {
                            RemoteImage(url: person.photo)
                                .frame(width: 320, height: 480)
                                .clipShape(RoundedRectangle(cornerRadius: 20))
                            VStack(alignment: .leading, spacing: 16) {
                                Text(person.name).font(.largeTitle).bold()
                                Text([person.knownFor, person.birthday, person.placeOfBirth].compactMap { $0 }.joined(separator: "  ·  "))
                                    .foregroundStyle(.secondary)
                                if let bio = person.biography {
                                    Text(bio).font(.callout).lineLimit(12)
                                }
                            }
                        }
                        .padding(.horizontal, 80)
                        if !person.credits.isEmpty {
                            MetaRowView(title: "Known for", items: person.credits)
                        }
                    }
                    .padding(.vertical, 60)
                }
            } else if failed {
                MessageView(title: "Couldn't load this person")
            } else {
                LoadingView()
            }
        }
        .task {
            guard person == nil else { return }
            person = try? await app.core.tmdbPerson(personId: personId)
            failed = person == nil
        }
    }
}
