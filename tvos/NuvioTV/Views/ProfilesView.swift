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

/// Settings ▸ Profiles: switch, add, rename, PIN, delete.
struct ProfilesSettingsView: View {
    @EnvironmentObject private var session: SessionModel
    @EnvironmentObject private var app: AppModel
    @State private var revision = 0
    @State private var newName = ""
    @State private var editing: UserProfile?
    @State private var editName = ""
    @State private var editPin = ""

    private var store: ProfileStore { app.core.profiles }

    var body: some View {
        List {
            Section("Profiles") {
                ForEach(store.profiles, id: \.index) { profile in
                    HStack(spacing: 20) {
                        Circle().fill(color(profile.colorHex)).frame(width: 50, height: 50)
                        Text(profile.name)
                        if profile.index == app.core.profileIndex { Text("Current").font(.caption).foregroundStyle(.secondary) }
                        Spacer()
                        if profile.index != app.core.profileIndex {
                            Button("Switch") { session.switchTo(profile) }
                        }
                        Button("Edit") {
                            editName = profile.name
                            editPin = profile.pin
                            editing = profile
                        }
                    }
                }
            }
            Section("Add a profile") {
                TextField("Name", text: $newName)
                Button("Add") {
                    _ = store.add(name: newName, colorHex: profileColors[store.profiles.count % profileColors.count])
                    newName = ""
                    revision += 1
                }
                .disabled(newName.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            Section {
                Text("Each profile has its own add-ons, library, progress, settings and accounts. With a Nuvio account, profile 2 syncs with your account's profile 2, and so on.")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .id(revision)
        .navigationTitle("Profiles")
        .sheet(item: Binding(get: { editing.map(ProfileBox.init) }, set: { editing = $0?.profile })) { box in
            VStack(alignment: .leading, spacing: 24) {
                Text("Edit profile").font(.title2).bold()
                TextField("Name", text: $editName)
                SecureField("PIN (digits, empty for none)", text: $editPin).keyboardType(.numberPad)
                HStack(spacing: 30) {
                    Button("Save") {
                        store.rename(index: box.profile.index, name: editName)
                        store.setPin(index: box.profile.index, pin: editPin)
                        editing = nil
                        revision += 1
                    }
                    if !box.profile.isPrimary && box.profile.index != app.core.profileIndex {
                        Button("Delete", role: .destructive) {
                            store.remove(index: box.profile.index)
                            editing = nil
                            revision += 1
                        }
                    }
                    Button("Cancel", role: .cancel) { editing = nil }
                }
            }
            .padding(80)
            .frame(maxWidth: 1200)
        }
    }
}
