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
    case catcher, scrubber, playPause, audio, subtitles, adjust, speed, next, panel, skip
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

            if let segment = model.activeSkipSegment, panel == nil, !model.showNextPrompt {
                VStack {
                    Spacer()
                    HStack {
                        Spacer()
                        Button(segment.label) { model.skip(segment) }
                            .focused($focus, equals: .skip)
                    }
                }
                .padding(.horizontal, 80)
                .padding(.bottom, controlsVisible ? 260 : 80)
                .onAppear { if !controlsVisible { focus = .skip } }
            }

            if model.showNextPrompt && panel == nil {
                nextEpisodeCard
            }

            if let panel {
                HStack {
                    Spacer()
                    panelView(panel)
                        .frame(width: 760)
                        .background(.ultraThinMaterial)
                        .background(Color.black.opacity(0.35))
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
            VStack(alignment: .leading, spacing: 44) {
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
                    playPauseButton
                        .padding(.trailing, 20)
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
        let trackHeight: CGFloat = isFocused ? 10 : 6
        let knob: CGFloat = isFocused ? 26 : 0
        return VStack(alignment: .leading, spacing: 18) {
            // The track sits centred in a box tall enough for the knob, so nothing overlaps the times.
            GeometryReader { geo in
                let played = geo.size.width * min(1, position / duration)
                let buffered = geo.size.width * min(1, (model.position + model.cacheSeconds) / duration)
                ZStack(alignment: .leading) {
                    Capsule().fill(Color.white.opacity(0.25))
                        .frame(height: trackHeight)
                    Capsule().fill(Color.white.opacity(0.4))
                        .frame(width: buffered, height: trackHeight)
                    Capsule().fill(Color.accentColor)
                        .frame(width: played, height: trackHeight)
                    Circle().fill(Color.white)
                        .frame(width: knob, height: knob)
                        .shadow(color: .black.opacity(0.4), radius: 6)
                        .offset(x: played - knob / 2)
                }
                .frame(width: geo.size.width, height: geo.size.height)
            }
            .frame(height: 30)
            HStack(spacing: 16) {
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
        .animation(.easeOut(duration: 0.15), value: isFocused)
    }

    /// Big round play/pause button leading the controls row.
    private var playPauseButton: some View {
        Button {
            model.togglePause()
            scheduleHide()
        } label: {
            Image(systemName: model.isPaused ? "play.fill" : "pause.fill")
                .font(.system(size: 34, weight: .bold))
                .frame(width: 90, height: 90)
        }
        .buttonStyle(RoundControlStyle())
        .focused($focus, equals: .playPause)
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
            optionPanel(title: "Playback speed", options: [0.5, 0.75, 1.0, 1.25, 1.5, 2.0].map { value in
                PanelOption(
                    id: "speed\(value)",
                    title: String(format: "%.2gx", value),
                    detail: value == 1 ? "Normal" : nil,
                    isSelected: abs(model.speed - value) < 0.01
                ) {
                    model.setSpeed(value)
                    self.panel = nil
                }
            })
        }
    }

    private func trackList(title: String, tracks: [MPVTrack], allowOff: Bool, select: @escaping (MPVTrack?) -> Void) -> some View {
        var options: [PanelOption] = []
        if allowOff {
            options.append(PanelOption(id: "off", title: "Off", detail: nil, isSelected: !tracks.contains(where: \.isSelected)) {
                select(nil)
            })
        }
        options += tracks.map { track in
            PanelOption(id: "track\(track.id)", title: track.label, detail: track.details, isSelected: track.isSelected) {
                select(track)
            }
        }
        return optionPanel(title: title, options: options)
    }

    /// Spaced list of card rows: the focused row is white, the active one tinted with a check.
    /// Opening the panel focuses the active row.
    private func optionPanel(title: String, options: [PanelOption]) -> some View {
        let focusTarget = options.first(where: \.isSelected)?.id ?? options.first?.id
        return ScrollView(.vertical) {
            VStack(alignment: .leading, spacing: 16) {
                Text(title)
                    .font(.title3.bold())
                    .padding(.bottom, 8)
                if options.isEmpty {
                    Text("No tracks").foregroundStyle(.secondary)
                }
                ForEach(options) { option in
                    let row = Button(action: option.action) {
                        HStack(alignment: .center, spacing: 20) {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(option.title)
                                    .font(.headline)
                                    .multilineTextAlignment(.leading)
                                    .fixedSize(horizontal: false, vertical: true)
                                if let detail = option.detail {
                                    Text(detail).font(.caption).opacity(0.75)
                                }
                            }
                            Spacer(minLength: 10)
                            if option.isSelected {
                                Image(systemName: "checkmark.circle.fill").font(.title3)
                            }
                        }
                    }
                    .buttonStyle(PanelRowStyle(isSelected: option.isSelected))
                    if option.id == focusTarget {
                        row.focused($focus, equals: .panel)
                    } else {
                        row
                    }
                }
            }
            .padding(.horizontal, 40)
            .padding(.vertical, 60)
        }
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

struct PanelOption: Identifiable {
    let id: String
    let title: String
    let detail: String?
    let isSelected: Bool
    let action: () -> Void
}

/// Card row for the player's side panels.
struct PanelRowStyle: ButtonStyle {
    let isSelected: Bool

    func makeBody(configuration: Configuration) -> some View {
        Row(configuration: configuration, isSelected: isSelected)
    }

    private struct Row: View {
        let configuration: ButtonStyle.Configuration
        let isSelected: Bool
        @Environment(\.isFocused) private var isFocused

        var body: some View {
            configuration.label
                .foregroundStyle(isFocused ? Color.black : Color.white)
                .padding(.horizontal, 24)
                .padding(.vertical, 18)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(
                    RoundedRectangle(cornerRadius: 18, style: .continuous)
                        .fill(isFocused ? Color.white : (isSelected ? Color.accentColor.opacity(0.45) : Color.white.opacity(0.07)))
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 18, style: .continuous)
                        .strokeBorder(Color.white.opacity(isSelected && !isFocused ? 0.6 : 0), lineWidth: 2)
                )
                .scaleEffect(configuration.isPressed ? 0.98 : (isFocused ? 1.03 : 1))
                .shadow(color: .black.opacity(isFocused ? 0.4 : 0), radius: 12, y: 6)
                .animation(.easeOut(duration: 0.12), value: isFocused)
        }
    }
}

/// Circular translucent control that grows and turns white when focused.
struct RoundControlStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        RoundControl(configuration: configuration)
    }

    private struct RoundControl: View {
        let configuration: ButtonStyle.Configuration
        @Environment(\.isFocused) private var isFocused

        var body: some View {
            configuration.label
                .foregroundStyle(isFocused ? Color.black : Color.white)
                .background(
                    Circle().fill(isFocused ? AnyShapeStyle(Color.white) : AnyShapeStyle(.ultraThinMaterial))
                )
                .scaleEffect(configuration.isPressed ? 0.94 : (isFocused ? 1.12 : 1))
                .shadow(color: .black.opacity(isFocused ? 0.45 : 0), radius: 14, y: 6)
                .animation(.easeOut(duration: 0.15), value: isFocused)
        }
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
