import SwiftUI
import NuvioShared

/// Picks the engine from settings: mpv (default) or Apple's AVPlayer.
struct PlayerContainer: View {
    @EnvironmentObject private var app: AppModel
    let request: PlaybackRequest

    var body: some View {
        if app.settings.playerEngine == AppSettings.companion.ENGINE_AVPLAYER {
            AVPlayerScreen(request: request)
        } else {
            PlayerScreen(request: request)
        }
    }
}

private enum PlayerFocus: Hashable {
    case catcher, scrubber, audio, subtitles, adjust, speed, next, panel
}

private enum PlayerPanel: Identifiable {
    case audio, subtitles, adjust, speed
    var id: Self { self }
}

struct PlayerScreen: View {
    @EnvironmentObject private var app: AppModel
    @Environment(\.dismiss) private var dismiss
    let request: PlaybackRequest

    @StateObject private var model = PlayerModel()
    @State private var controlsVisible = true
    @State private var panel: PlayerPanel?
    @State private var scrubTarget: Double?
    @State private var scrubBase: Double = 0
    @State private var hideTask: Task<Void, Never>?
    @State private var commitTask: Task<Void, Never>?
    @FocusState private var focus: PlayerFocus?

    private var seekStep: Double { Double(app.settings.seekStepSeconds) }

    var body: some View {
        ZStack {
            MPVVideoView(controller: model.controller, onScrub: handleSwipe)
                .ignoresSafeArea()
                .background(WindowReader { model.window = $0 })

            // Invisible focus target while the controls are hidden.
            Button(action: { showControls(focusScrubber: true) }) { Color.clear }
                .buttonStyle(InvisibleButtonStyle())
                .focused($focus, equals: .catcher)
                .onMoveCommand(perform: handleMoveWhileHidden)
                .disabled(controlsVisible || panel != nil)
                .ignoresSafeArea()

            if model.isBuffering && !model.isPaused && model.errorMessage == nil {
                ProgressView().scaleEffect(1.6)
            }

            if controlsVisible && panel == nil {
                controls.transition(.opacity)
            } else if let scrubTarget {
                VStack { Spacer(); timeline(position: scrubTarget).padding(80) }
            }

            if model.showNextPrompt && panel == nil {
                nextEpisodeCard
            }

            if let panel {
                HStack {
                    Spacer()
                    panelView(panel)
                        .frame(width: 720)
                        .background(.ultraThinMaterial)
                        .focusSection()
                }
                .ignoresSafeArea()
                .transition(.move(edge: .trailing))
            }

            if let error = model.errorMessage {
                errorOverlay(error)
            }
        }
        .animation(.easeInOut(duration: 0.2), value: controlsVisible)
        .animation(.easeInOut(duration: 0.2), value: panel)
        .onAppear {
            model.start(request, core: app.core)
            showControls(focusScrubber: true)
        }
        .onDisappear { model.stop() }
        .onPlayPauseCommand {
            model.togglePause()
            showControls(focusScrubber: false)
        }
        .onExitCommand {
            if panel != nil {
                panel = nil
                showControls(focusScrubber: true)
            } else if controlsVisible && !model.isPaused {
                hideControls()
            } else {
                dismiss()
            }
        }
        .onChange(of: model.isPaused) { _, paused in
            if paused { showControls(focusScrubber: false) } else { scheduleHide() }
        }
    }

    // MARK: Controls overlay

    private var controls: some View {
        VStack(alignment: .leading, spacing: 0) {
            LinearGradient(colors: [.black.opacity(0.8), .clear], startPoint: .top, endPoint: .bottom)
                .frame(height: 220)
                .overlay(alignment: .topLeading) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(model.request?.title ?? request.title).font(.title3).bold().lineLimit(1)
                        if let name = model.request?.streamName, !name.isEmpty {
                            Text(name.replacingOccurrences(of: "\n", with: " · "))
                                .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                        }
                    }
                    .padding(.horizontal, 80)
                    .padding(.top, 60)
                }
            Spacer()
            VStack(alignment: .leading, spacing: 30) {
                Button(action: {
                    model.togglePause()
                    scheduleHide()
                }) {
                    if model.request?.isLive == true && model.duration <= 0 {
                        HStack(spacing: 16) {
                            Text("LIVE").font(.caption.bold()).padding(.horizontal, 12).padding(.vertical, 4)
                                .background(Color.red, in: Capsule())
                            Text("▲ / ▼ to change channel").font(.caption).foregroundStyle(.secondary)
                        }
                    } else {
                        timeline(position: scrubTarget ?? model.position)
                    }
                }
                .buttonStyle(InvisibleButtonStyle())
                .focused($focus, equals: .scrubber)
                .onMoveCommand(perform: handleMoveOnScrubber)

                HStack(spacing: 30) {
                    controlButton("Audio", "speaker.wave.2", .audio) { open(.audio) }
                    controlButton("Subtitles", "captions.bubble", .subtitles) { open(.subtitles) }
                    controlButton("Sync & size", "slider.horizontal.3", .adjust) { open(.adjust) }
                    controlButton(speedLabel, "speedometer", .speed) { open(.speed) }
                    if model.nextVideo != nil {
                        controlButton("Next episode", "forward.end", .next) { model.playNext() }
                    }
                }
                .focusSection()
            }
            .padding(.horizontal, 80)
            .padding(.bottom, 60)
            .background(
                LinearGradient(colors: [.clear, .black.opacity(0.85)], startPoint: .top, endPoint: .bottom)
                    .ignoresSafeArea()
            )
        }
        .ignoresSafeArea()
    }

    private var speedLabel: String {
        model.speed == 1 ? "Speed" : String(format: "%.2gx", model.speed)
    }

    private func controlButton(_ title: String, _ icon: String, _ field: PlayerFocus, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Label(title, systemImage: icon).font(.callout)
        }
        .focused($focus, equals: field)
    }

    private func timeline(position: Double) -> some View {
        let isFocused = focus == .scrubber
        let duration = max(model.duration, 1)
        return VStack(alignment: .leading, spacing: 12) {
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(Color.white.opacity(0.25))
                    Capsule().fill(Color.white.opacity(0.4))
                        .frame(width: geo.size.width * min(1, (model.position + model.cacheSeconds) / duration))
                    Capsule().fill(Color.accentColor)
                        .frame(width: geo.size.width * min(1, position / duration))
                    Circle().fill(Color.white)
                        .frame(width: isFocused ? 28 : 0, height: isFocused ? 28 : 0)
                        .offset(x: geo.size.width * min(1, position / duration) - (isFocused ? 14 : 0))
                }
            }
            .frame(height: isFocused ? 12 : 8)
            HStack {
                Image(systemName: model.isPaused ? "pause.fill" : "play.fill")
                Text(formatTime(position))
                if scrubTarget != nil {
                    Text(String(format: "(%@%@)", position >= model.position ? "+" : "-", formatTime(abs(position - model.position))))
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Text("-" + formatTime(max(0, model.duration - position)))
            }
            .font(.callout.monospacedDigit())
        }
    }

    // MARK: Panels

    @ViewBuilder
    private func panelView(_ panel: PlayerPanel) -> some View {
        switch panel {
        case .audio:
            trackList(title: "Audio", tracks: model.audioTracks, allowOff: false) { model.selectAudio($0!) }
        case .subtitles:
            trackList(
                title: model.isLoadingAddonSubtitles ? "Subtitles (loading add-ons…)" : "Subtitles",
                tracks: model.subtitleTracks,
                allowOff: true
            ) { model.selectSubtitle($0) }
        case .adjust:
            adjustPanel
        case .speed:
            List {
                Section("Playback speed") {
                    ForEach([0.5, 0.75, 1.0, 1.25, 1.5, 2.0], id: \.self) { value in
                        Button {
                            model.setSpeed(value)
                            self.panel = nil
                        } label: {
                            HStack {
                                Text(String(format: "%.2gx", value))
                                Spacer()
                                if abs(model.speed - value) < 0.01 { Image(systemName: "checkmark") }
                            }
                        }
                    }
                }
            }
            .focused($focus, equals: .panel)
        }
    }

    private func trackList(title: String, tracks: [MPVTrack], allowOff: Bool, select: @escaping (MPVTrack?) -> Void) -> some View {
        List {
            Section(title) {
                if allowOff {
                    Button {
                        select(nil)
                    } label: {
                        HStack {
                            Text("Off")
                            Spacer()
                            if !tracks.contains(where: \.isSelected) { Image(systemName: "checkmark") }
                        }
                    }
                }
                if tracks.isEmpty {
                    Text("No tracks").foregroundStyle(.secondary)
                }
                ForEach(tracks) { track in
                    Button {
                        select(track)
                    } label: {
                        HStack {
                            Text(track.displayName).lineLimit(2)
                            Spacer()
                            if track.isSelected { Image(systemName: "checkmark") }
                        }
                    }
                }
            }
        }
        .focused($focus, equals: .panel)
    }

    private var adjustPanel: some View {
        List {
            Section("Subtitle delay") {
                stepper(value: String(format: "%+.2f s", model.subtitleDelay)) {
                    model.subtitleDelay -= 0.25
                } plus: {
                    model.subtitleDelay += 0.25
                }
            }
            Section("Audio delay") {
                stepper(value: String(format: "%+.2f s", model.audioDelay)) {
                    model.audioDelay -= 0.05
                } plus: {
                    model.audioDelay += 0.05
                }
            }
            Section("Subtitle size") {
                stepper(value: "\(Int(model.subtitleScale * 100))%") {
                    model.subtitleScale = max(0.5, model.subtitleScale - 0.1)
                } plus: {
                    model.subtitleScale = min(2.0, model.subtitleScale + 0.1)
                }
            }
            Section {
                Button("Use these as defaults") { model.rememberSubtitleTweaks() }
            }
        }
        .focused($focus, equals: .panel)
    }

    private func stepper(value: String, minus: @escaping () -> Void, plus: @escaping () -> Void) -> some View {
        HStack(spacing: 30) {
            Button(action: minus) { Image(systemName: "minus") }
            Text(value).font(.headline.monospacedDigit()).frame(minWidth: 180)
            Button(action: plus) { Image(systemName: "plus") }
        }
    }

    // MARK: Next episode / errors

    private var nextEpisodeCard: some View {
        VStack {
            Spacer()
            HStack {
                Spacer()
                VStack(alignment: .leading, spacing: 16) {
                    Text("Up next").font(.caption).foregroundStyle(.secondary)
                    if let next = model.nextVideo {
                        Text("\(next.episodeLabel ?? "") · \(next.title)").font(.headline).lineLimit(1)
                    }
                    HStack(spacing: 20) {
                        Button(model.isResolvingNext ? "Finding stream…" : "Play now (\(Int(model.remaining))s)") {
                            model.playNext()
                        }
                        .disabled(model.isResolvingNext)
                        Button("Dismiss") { model.nextPromptDismissed = true }
                    }
                }
                .padding(40)
                .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 24))
                .frame(maxWidth: 800)
                .focusSection()
            }
        }
        .padding(80)
    }

    private func errorOverlay(_ message: String) -> some View {
        VStack(spacing: 30) {
            MessageView(title: "Playback failed", message: message)
            Button("Back") { dismiss() }
        }
        .background(Color.black.opacity(0.85))
    }

    // MARK: Input handling

    private func open(_ newPanel: PlayerPanel) {
        hideTask?.cancel()
        panel = newPanel
        focus = .panel
    }

    private func showControls(focusScrubber: Bool) {
        controlsVisible = true
        if focusScrubber { focus = .scrubber }
        scheduleHide()
    }

    private func hideControls() {
        hideTask?.cancel()
        controlsVisible = false
        focus = .catcher
    }

    private func scheduleHide() {
        hideTask?.cancel()
        hideTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 5_000_000_000)
            guard !Task.isCancelled, !model.isPaused, panel == nil, scrubTarget == nil else { return }
            hideControls()
        }
    }

    private func handleMoveWhileHidden(_ direction: MoveCommandDirection) {
        if model.request?.isLive == true {
            switch direction {
            case .up: model.zap(by: -1); return
            case .down: model.zap(by: 1); return
            default: break
            }
        }
        switch direction {
        case .left: stepScrub(-seekStep)
        case .right: stepScrub(seekStep)
        default: showControls(focusScrubber: true)
        }
    }

    private func handleMoveOnScrubber(_ direction: MoveCommandDirection) {
        switch direction {
        case .left: stepScrub(-seekStep)
        case .right: stepScrub(seekStep)
        default: break
        }
        scheduleHide()
    }

    /// Accumulates d-pad presses into one seek, committed shortly after the last press.
    private func stepScrub(_ delta: Double) {
        let base = scrubTarget ?? model.position
        scrubTarget = min(max(0, base + delta), max(model.duration - 1, 0))
        commitScrubSoon()
    }

    private func handleSwipe(_ dx: CGFloat, _ state: UIGestureRecognizer.State) {
        guard model.duration > 0 else { return }
        switch state {
        case .began:
            scrubBase = scrubTarget ?? model.position
        case .changed:
            // A full swipe across the touch surface ≈ 10 % of the runtime (min 2 minutes).
            let span = max(120, model.duration * 0.1)
            scrubTarget = min(max(0, scrubBase + Double(dx) * span), model.duration - 1)
            hideTask?.cancel()
        case .ended, .cancelled:
            commitScrubSoon()
        default:
            break
        }
    }

    private func commitScrubSoon() {
        commitTask?.cancel()
        commitTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 700_000_000)
            guard !Task.isCancelled, let target = scrubTarget else { return }
            model.seek(to: target)
            scrubTarget = nil
            scheduleHide()
        }
    }

    private func formatTime(_ seconds: Double) -> String {
        guard seconds.isFinite else { return "--:--" }
        let total = Int(seconds.rounded())
        let h = total / 3600, m = (total % 3600) / 60, s = total % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%02d:%02d", m, s)
    }
}

/// Button that keeps focus/select behaviour but draws nothing extra.
struct InvisibleButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.contentShape(Rectangle())
    }
}

/// Gives the player model access to its UIWindow (for display criteria / match content).
struct WindowReader: UIViewRepresentable {
    let onWindow: (UIWindow?) -> Void

    func makeUIView(context: Context) -> UIView {
        let view = WindowReaderView()
        view.onWindow = onWindow
        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {}

    final class WindowReaderView: UIView {
        var onWindow: ((UIWindow?) -> Void)?
        override func didMoveToWindow() {
            super.didMoveToWindow()
            onWindow?(window)
        }
    }
}
