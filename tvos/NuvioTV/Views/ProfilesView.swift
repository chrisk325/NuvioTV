import SwiftUI
import NuvioShared

private func color(_ hex: String) -> Color {
    var value: UInt64 = 0
    Scanner(string: hex.trimmingCharacters(in: CharacterSet(charactersIn: "#"))).scanHexInt64(&value)
    return Color(red: Double((value >> 16) & 0xFF) / 255, green: Double((value >> 8) & 0xFF) / 255, blue: Double(value & 0xFF) / 255)
}

private let profileColors = ["#1E88E5", "#E53935", "#43A047", "#FB8C00", "#8E24AA", "#00ACC1", "#F4511E", "#6D4C41"]

/// "Who's watching?" (Android ProfileSelectionScreen).
struct ProfilePickerView: View {
    @EnvironmentObject private var session: SessionModel
    @State private var pinFor: UserProfile?
    @State private var pin = ""
    @State private var pinError = false

    var body: some View {
        VStack(spacing: 60) {
            Text("Who's watching?").font(.largeTitle).bold()
            HStack(spacing: 60) {
                ForEach(session.profiles, id: \.index) { profile in
                    VStack(spacing: 20) {
                        Button {
                            if profile.isLocked { pin = ""; pinError = false; pinFor = profile } else { session.switchTo(profile) }
                        } label: {
                            ZStack {
                                Circle().fill(color(profile.colorHex))
                                Text(String(profile.name.prefix(1)).uppercased()).font(.system(size: 80, weight: .bold))
                            }
                            .frame(width: 220, height: 220)
                        }
                        .buttonStyle(.card)
                        .clipShape(Circle())
                        HStack(spacing: 8) {
                            Text(profile.name).font(.headline)
                            if profile.isLocked { Image(systemName: "lock.fill").font(.caption) }
                        }
                    }
                }
            }
        }
        .sheet(item: Binding(get: { pinFor.map(ProfileBox.init) }, set: { pinFor = $0?.profile })) { box in
            VStack(spacing: 30) {
                Text("Enter PIN for \(box.profile.name)").font(.title2)
                SecureField("PIN", text: $pin).keyboardType(.numberPad).frame(maxWidth: 500)
                if pinError { Text("Wrong PIN").foregroundStyle(.red) }
                HStack(spacing: 30) {
                    Button("Open") {
                        if session.app.core.profiles.checkPin(index: box.profile.index, pin: pin) {
                            pinFor = nil
                            session.switchTo(box.profile)
                        } else {
                            pinError = true
                        }
                    }
                    Button("Cancel", role: .cancel) { pinFor = nil }
                }
            }
            .padding(80)
        }
    }
}

private struct ProfileBox: Identifiable {
    let profile: UserProfile
    var id: Int32 { profile.index }
}

/// Settings ▸ Profiles: one row per profile; selecting it opens a card to switch, rename,
/// set a PIN or delete (with confirmation). Any profile but the primary one can be deleted;
/// deleting the one in use moves to the primary profile.
struct ProfilesSettingsView: View {
    @EnvironmentObject private var session: SessionModel
    @EnvironmentObject private var app: AppModel
    @State private var revision = 0
    @State private var newName = ""
    @State private var selected: UserProfile?

    private var store: ProfileStore { app.core.profiles }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Text("Profiles").font(.headline).foregroundStyle(.secondary)
                ForEach(store.profiles, id: \.index) { profile in
                    Button { selected = profile } label: {
                        HStack(spacing: 24) {
                            ZStack {
                                Circle().fill(color(profile.colorHex))
                                Text(String(profile.name.prefix(1)).uppercased())
                                    .font(.title3.bold())
                                    .foregroundStyle(.white)
                            }
                            .frame(width: 64, height: 64)
                            Text(profile.name).font(.headline)
                            if profile.isLocked { Image(systemName: "lock.fill").font(.caption) }
                            if profile.index == app.core.profileIndex {
                                Text("Current").font(.caption.weight(.semibold))
                                    .padding(.horizontal, 12).padding(.vertical, 4)
                                    .background(Capsule().strokeBorder(lineWidth: 1.5))
                            }
                            Spacer()
                            Image(systemName: "chevron.right").font(.callout)
                        }
                    }
                    .buttonStyle(ProfileRowStyle())
                }

                Text("Add a profile").font(.headline).foregroundStyle(.secondary).padding(.top, 30)
                HStack(spacing: 24) {
                    TextField("Name", text: $newName)
                    Button("Add") {
                        _ = store.add(name: newName, colorHex: profileColors[store.profiles.count % profileColors.count])
                        newName = ""
                        revision += 1
                    }
                    .disabled(newName.trimmingCharacters(in: .whitespaces).isEmpty)
                }

                Text("Each profile has its own add-ons, library, progress, settings and accounts. With a Nuvio account, profile 2 syncs with your account's profile 2, and so on.")
                    .font(.caption).foregroundStyle(.secondary)
                    .padding(.top, 20)
            }
            .padding(.horizontal, 40)
            .padding(.vertical, 30)
        }
        .id(revision)
        .sheet(item: Binding(get: { selected.map(ProfileBox.init) }, set: { selected = $0?.profile })) { box in
            ProfileEditor(
                profile: box.profile,
                isCurrent: box.profile.index == app.core.profileIndex,
                store: store,
                onSwitch: {
                    selected = nil
                    session.switchTo(box.profile)
                },
                onDone: {
                    selected = nil
                    revision += 1
                },
                onDelete: { deleteProfile(box.profile) }
            )
        }
    }

    private func deleteProfile(_ profile: UserProfile) {
        selected = nil
        let wasCurrent = profile.index == app.core.profileIndex
        let primary = store.profiles.first { $0.isPrimary }
        store.remove(index: profile.index)
        if wasCurrent, let primary {
            // The profile in use is gone: carry on as the primary profile.
            session.switchTo(primary)
        } else {
            revision += 1
        }
    }
}

/// Card for one profile: switch to it, rename, PIN, delete.
private struct ProfileEditor: View {
    let profile: UserProfile
    let isCurrent: Bool
    let store: ProfileStore
    let onSwitch: () -> Void
    let onDone: () -> Void
    let onDelete: () -> Void

    @State private var name = ""
    @State private var pin = ""
    @State private var confirmDelete = false

    var body: some View {
        VStack(alignment: .leading, spacing: 26) {
            HStack(spacing: 24) {
                ZStack {
                    Circle().fill(color(profile.colorHex))
                    Text(String(profile.name.prefix(1)).uppercased()).font(.title.bold()).foregroundStyle(.white)
                }
                .frame(width: 90, height: 90)
                VStack(alignment: .leading, spacing: 6) {
                    Text(profile.name).font(.title2).bold()
                    Text(isCurrent ? "Current profile" : (profile.isPrimary ? "Primary profile" : "Profile"))
                        .font(.callout).foregroundStyle(.secondary)
                }
            }
            if !isCurrent {
                Button { onSwitch() } label: { Label("Switch to this profile", systemImage: "person.crop.circle") }
            }
            TextField("Name", text: $name)
            SecureField("PIN (digits, empty for none)", text: $pin).keyboardType(.numberPad)
            HStack(spacing: 30) {
                Button("Save") {
                    let trimmed = name.trimmingCharacters(in: .whitespaces)
                    if !trimmed.isEmpty { store.rename(index: profile.index, name: trimmed) }
                    store.setPin(index: profile.index, pin: pin)
                    onDone()
                }
                if !profile.isPrimary {
                    Button(role: .destructive) { confirmDelete = true } label: {
                        Label("Delete profile", systemImage: "trash")
                    }
                }
                Button("Cancel", role: .cancel) { onDone() }
            }
            if profile.isPrimary {
                Text("The primary profile can't be deleted.").font(.caption).foregroundStyle(.secondary)
            }
        }
        .padding(70)
        .frame(maxWidth: 1100)
        .onAppear { name = profile.name; pin = profile.pin }
        .confirmationDialog("Delete \(profile.name)?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Delete", role: .destructive) { onDelete() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(isCurrent
                 ? "Its add-ons, library, progress and settings on this Apple TV are removed, and you'll switch to the primary profile."
                 : "Its add-ons, library, progress and settings on this Apple TV are removed.")
        }
    }
}

/// Settings row: readable in both states (white with dark text when focused).
private struct ProfileRowStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        Row(configuration: configuration)
    }

    private struct Row: View {
        let configuration: ButtonStyle.Configuration
        @Environment(\.isFocused) private var isFocused

        var body: some View {
            configuration.label
                .foregroundStyle(isFocused ? Color.black : Color.white)
                .padding(.horizontal, 26)
                .padding(.vertical, 16)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(
                    RoundedRectangle(cornerRadius: 18, style: .continuous)
                        .fill(isFocused ? Color.white : Color.white.opacity(0.08))
                )
                .scaleEffect(configuration.isPressed ? 0.98 : (isFocused ? 1.02 : 1))
                .animation(.easeOut(duration: 0.12), value: isFocused)
        }
    }
}
