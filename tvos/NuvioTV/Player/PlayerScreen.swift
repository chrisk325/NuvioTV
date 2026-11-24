import SwiftUI
import NuvioShared

/// Picks the engine from settings: mpv (default) or Apple's AVPlayer.
struct PlayerContainer: View {
    @EnvironmentObject private var app: AppModel
    let request: PlaybackRequest

    var body: some View {
        let engine = app.settings.playerEngine
        if let external = ExternalPlayer(engine: engine), !request.isTrailer {
            ExternalPlayerScreen(player: external, request: request)
        } else if engine == AppSettings.companion.ENGINE_AVPLAYER {
            AVPlayerScreen(request: request)
        } else {
            PlayerScreen(request: request)
        }
    }
}

private enum PlayerFocus: Hashable {
    case catcher, scrubber, playPause, audio, dialogue, subtitles, adjust, speed, aspect, next, panel, skip, errorBack
}

private enum PlayerPanel: Identifiable {
    case audio, subtitles, adjust, speed, aspect
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
    /// Closes the player a few seconds after playback fails.
    @State private var errorCloseTask: Task<Void, Never>?
    @State private var errorCountdown = 0
    private static let errorCloseSeconds = 5
    @FocusState private var focus: PlayerFocus?

    private var seekStep: Double { Double(app.settings.seekStepSeconds) }

    var body: some View {
        ZStack {
            MPVVideoView(controller: model.controller, onScrub: handleSwipe)
                .ignoresSafeArea()
                .background(WindowReader { window in
                    // Only remember a real window; leaving the screen reports nil.
                    if let window { model.window = window }
                })

            // Invisible focus target while the controls are hidden.
            Button(action: { if !model.isStarting { showControls(focusScrubber: true) } }) { Color.clear }
                .buttonStyle(InvisibleButtonStyle())
                .focused($focus, equals: .catcher)
                .onMoveCommand(perform: handleMoveWhileHidden)
                .disabled(controlsVisible || panel != nil || model.errorMessage != nil)
                .ignoresSafeArea()

            if model.isBuffering && !model.isPaused && model.errorMessage == nil && !model.isStarting {
                ProgressView().scaleEffect(1.6)
            }

            if controlsVisible && panel == nil && model.errorMessage == nil {
                controls.transition(.opacity)
            } else if let scrubTarget {
                VStack {
                    Spacer()
                    TimelineBar(clock: model.clock, scrubTarget: scrubTarget, isFocused: false).padding(80)
                }
            }

            if let segment = model.activeSkipSegment, panel == nil, !model.showNextPrompt {
                VStack {
                    Spacer()
                    HStack {
                        Spacer()
                        Button(segment.label) { model.skip(segment) }
                            .buttonStyle(GlassCapsuleButtonStyle())
                            .focused($focus, equals: .skip)
                    }
                }
                .padding(.horizontal, 80)
                .padding(.bottom, controlsVisible ? 330 : 80)
                .onAppear { if !controlsVisible { focus = .skip } }
            }

            if model.showNextPrompt && panel == nil {
                nextEpisodeCard
            }

            if let notice = model.autoSkipNotice {
                VStack {
                    HStack {
                        Spacer()
                        Label(notice, systemImage: "forward.fill")
                            .font(.callout.weight(.semibold))
                            .padding(.horizontal, 28)
                            .padding(.vertical, 16)
                            .liquidGlass(in: Capsule(), tint: .black.opacity(0.25))
                    }
                    Spacer()
                }
                .padding(60)
                .transition(.opacity)
                .allowsHitTesting(false)
            }

            if let panel {
                HStack {
                    Spacer()
                    panelView(panel)
                        .frame(width: 760)
                        .frame(maxHeight: .infinity)
                        .liquidGlass(in: RoundedRectangle(cornerRadius: 44, style: .continuous), tint: .black.opacity(0.45))
                        .padding(.vertical, 40)
                        .padding(.trailing, 40)
                        .focusSection()
                }
                .ignoresSafeArea()
                .transition(.move(edge: .trailing))
            }

            // Opening a stream (loading, frame-rate / HDR switch, first frames): a cover instead of a
            // frozen first frame; the video appears once it is really playing.
            if model.isStarting && model.errorMessage == nil {
                StartingCover(meta: (model.request ?? request).meta, title: (model.request ?? request).title)
                    .transition(.opacity)
                    .allowsHitTesting(false)
            }

            if let error = model.errorMessage {
                errorOverlay(error)
            }
        }
        .animation(.easeOut(duration: 0.35), value: model.isStarting)
        .animation(.easeInOut(duration: 0.2), value: controlsVisible)
        .animation(.easeInOut(duration: 0.2), value: panel)
        .animation(.easeInOut(duration: 0.25), value: model.autoSkipNotice)
        .onAppear {
            model.start(request, core: app.core)
            // The controls come up once the video is showing (see isStarting below).
            if model.isStarting { focus = .catcher } else { showControls(focusScrubber: true) }
        }
        .onChange(of: model.isStarting) { _, starting in
            if !starting && model.errorMessage == nil { showControls(focusScrubber: true) }
        }
        .onChange(of: model.errorMessage) { _, message in
            if message != nil { showError() } else { cancelErrorClose() }
        }
        .onDisappear {
            cancelErrorClose()
            model.stop()
        }
        .onPlayPauseCommand {
            model.togglePause()
            showControls(focusScrubber: false)
        }
        .onExitCommand {
            if model.errorMessage != nil {
                leaveAfterError()
            } else if panel != nil {
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
            LinearGradient(colors: [.black.opacity(0.45), .clear], startPoint: .top, endPoint: .bottom)
                .frame(height: 220)
                .overlay(alignment: .topLeading) {
                    // Title and stream on a Liquid Glass pill.
                    VStack(alignment: .leading, spacing: 6) {
                        Text(model.request?.title ?? request.title).font(.title3).bold().lineLimit(1)
                        if let name = model.request?.streamName, !name.isEmpty {
                            Text(name.replacingOccurrences(of: "\n", with: " · "))
                                .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                        }
                    }
                    .padding(.horizontal, 36)
                    .padding(.vertical, 22)
                    .liquidGlass(in: RoundedRectangle(cornerRadius: 34, style: .continuous), tint: .black.opacity(0.15))
                    .frame(maxWidth: 1300, alignment: .leading)
                    .padding(.horizontal, 60)
                    .padding(.top, 50)
                }
            Spacer()
            VStack(alignment: .leading, spacing: 44) {
                Button(action: {
                    model.togglePause()
                    scheduleHide()
                }) {
                    // Live channels never show a seek bar (no scrubbing on live TV).
                    if model.request?.isLive == true {
                        HStack(spacing: 16) {
                            Text("LIVE").font(.caption.bold()).padding(.horizontal, 14).padding(.vertical, 6)
                                .liquidGlass(in: Capsule(), tint: .red.opacity(0.75))
                            Text("▲ / ▼ to change channel").font(.caption).foregroundStyle(.secondary)
                        }
                    } else {
                        TimelineBar(clock: model.clock, scrubTarget: scrubTarget, isFocused: focus == .scrubber)
                    }
                }
                .buttonStyle(InvisibleButtonStyle())
                .focused($focus, equals: .scrubber)
                .onMoveCommand(perform: handleMoveOnScrubber)

                HStack(spacing: 30) {
                    playPauseButton
                        .padding(.trailing, 20)
                    controlButton("Audio", "speaker.wave.2", .audio) { open(.audio) }
                    // One press steps Off → Low → Medium → High → Off.
                    controlButton(dialogueLabel, "waveform", .dialogue) {
                        model.dialogueBoost = (model.dialogueBoost + 1) % DialogueEnhancer.levels.count
                        scheduleHide()
                    }
                    controlButton("Subtitles", "captions.bubble", .subtitles) { open(.subtitles) }
                    controlButton("Sync & size", "slider.horizontal.3", .adjust) { open(.adjust) }
                    controlButton(speedLabel, "speedometer", .speed) { open(.speed) }
                    controlButton(model.aspectMode == .fit ? "Resize" : model.aspectMode.title, "aspectratio", .aspect) { open(.aspect) }
                    if model.nextVideo != nil {
                        controlButton("Next episode", "forward.end", .next) { model.playNext() }
                    }
                }
                .focusSection()
            }
            // Scrubber and buttons on one Liquid Glass dock.
            .padding(.horizontal, 44)
            .padding(.top, 36)
            .padding(.bottom, 40)
            .liquidGlass(in: RoundedRectangle(cornerRadius: 44, style: .continuous), tint: .black.opacity(0.2))
            .padding(.horizontal, 60)
            .padding(.bottom, 50)
            // The shade spans the whole width (it used to stop at the dock's edge, a dark box).
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                LinearGradient(colors: [.clear, .black.opacity(0.45)], startPoint: .top, endPoint: .bottom)
                    .padding(.top, -120) // a longer, softer fade above the dock
                    .ignoresSafeArea()
            )
        }
        .ignoresSafeArea()
    }

    private var dialogueLabel: String {
        model.dialogueBoost == 0 ? "Dialogue" : "Dialogue: \(DialogueEnhancer.levels[model.dialogueBoost])"
    }

    private var speedLabel: String {
        model.speed == 1 ? "Speed" : String(format: "%.2gx", model.speed)
    }

    /// Icon-only glass button that shows its name when focused, so the row always fits on one line.
    private func controlButton(_ title: String, _ icon: String, _ field: PlayerFocus, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            PlayerControlLabel(title: title, icon: icon, expanded: focus == field)
        }
        .buttonStyle(GlassCapsuleButtonStyle())
        .focused($focus, equals: field)
        .accessibilityLabel(title)
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
            trackList(title: "Audio", tracks: model.audioTracks, allowOff: false,
                      header: AnyView(dialogueBoostRow)) { model.selectAudio($0!) }
        case .subtitles:
            trackList(
                title: model.isLoadingAddonSubtitles ? "Subtitles (loading add-ons…)" : "Subtitles",
                tracks: model.subtitleTracks,
                allowOff: true,
                header: AnyView(subtitleBrightnessRow)
            ) { model.selectSubtitle($0) }
        case .adjust:
            adjustPanel
        case .aspect:
            optionPanel(title: "Picture size", options: PlayerAspectMode.allCases.map { mode in
                PanelOption(id: "aspect-\(mode.rawValue)", title: mode.title, detail: mode.detail,
                            isSelected: model.aspectMode == mode) {
                    model.setAspect(mode)
                    self.panel = nil
                }
            })
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

    /// Dialogue enhancer at the top of the Audio panel: Off / Low / Medium / High.
    private var dialogueBoostRow: some View {
        VStack(alignment: .leading, spacing: 14) {
            Label("Dialogue boost", systemImage: "waveform").font(.headline)
            HStack(spacing: 14) {
                ForEach(Array(DialogueEnhancer.levels.enumerated()), id: \.offset) { index, name in
                    Button {
                        model.dialogueBoost = index
                    } label: {
                        HStack(spacing: 8) {
                            if model.dialogueBoost == index { Image(systemName: "checkmark") }
                            Text(name)
                        }
                    }
                    .buttonStyle(GlassCapsuleButtonStyle())
                }
            }
            Text("Lifts voices over music and effects, and evens out loud and quiet scenes.")
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        .padding(.bottom, 12)
        .focusSection()
    }

    /// Subtitle brightness, 0–100 % in steps of 10, at the top of the Subtitles panel.
    private var subtitleBrightnessRow: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 24) {
                Label("Brightness", systemImage: "sun.max").font(.headline)
                Spacer()
                Button {
                    model.subtitleBrightness = max(0, model.subtitleBrightness - 10)
                } label: {
                    Image(systemName: "minus")
                }
                .buttonStyle(GlassCapsuleButtonStyle())
                .disabled(model.subtitleBrightness <= 0)
                Text("\(model.subtitleBrightness)%")
                    .font(.headline.monospacedDigit())
                    .frame(minWidth: 90)
                Button {
                    model.subtitleBrightness = min(100, model.subtitleBrightness + 10)
                } label: {
                    Image(systemName: "plus")
                }
                .buttonStyle(GlassCapsuleButtonStyle())
                .disabled(model.subtitleBrightness >= 100)
            }
            Text("Text and styled subtitles; picture-based ones (PGS / VobSub) keep their own brightness.")
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        .padding(.bottom, 12)
        .focusSection()
    }

    private func trackList(title: String, tracks: [MPVTrack], allowOff: Bool, header: AnyView? = nil,
                           select: @escaping (MPVTrack?) -> Void) -> some View {
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
        return optionPanel(title: title, options: options, header: header)
    }

    /// Spaced list of card rows: the focused row is white, the active one tinted with a check.
    /// Opening the panel focuses the active row.
    private func optionPanel(title: String, options: [PanelOption], header: AnyView? = nil) -> some View {
        let focusTarget = options.first(where: \.isSelected)?.id ?? options.first?.id
        return ScrollView(.vertical) {
            VStack(alignment: .leading, spacing: 16) {
                Text(title)
                    .font(.title3.bold())
                    .padding(.bottom, 8)
                if let header { header }
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
                        Button {
                            model.playNext()
                        } label: {
                            if model.isResolvingNext {
                                Text("Finding stream…")
                            } else {
                                NextCountdownLabel(clock: model.clock)
                            }
                        }
                        .disabled(model.isResolvingNext)
                        Button("Dismiss") { model.nextPromptDismissed = true }
                    }
                }
                .padding(40)
                .liquidGlass(in: RoundedRectangle(cornerRadius: 36, style: .continuous), tint: .black.opacity(0.3))
                .frame(maxWidth: 800)
                .focusSection()
            }
        }
        .padding(80)
    }

    private func errorOverlay(_ message: String) -> some View {
        ZStack {
            Color.black.opacity(0.85).ignoresSafeArea()
            VStack(spacing: 30) {
                MessageView(title: "Playback failed", message: message)
                    .frame(maxHeight: 500)
                Button(errorCountdown > 0 ? "Back (\(errorCountdown))" : "Back") { leaveAfterError() }
                    .focused($focus, equals: .errorBack)
            }
            .focusSection()
        }
    }

    /// Playback failed: nothing else on screen takes the remote, Back is focused, and the player
    /// closes by itself after a few seconds.
    private func showError() {
        hideTask?.cancel()
        panel = nil
        controlsVisible = false
        scrubTarget = nil
        focus = .errorBack
        errorCloseTask?.cancel()
        errorCountdown = Self.errorCloseSeconds
        errorCloseTask = Task { @MainActor in
            while errorCountdown > 0 {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                if Task.isCancelled { return }
                errorCountdown -= 1
            }
            leaveAfterError()
        }
        // Focus moves once the overlay is on screen.
        DispatchQueue.main.async { focus = .errorBack }
    }

    private func cancelErrorClose() {
        errorCloseTask?.cancel()
        errorCloseTask = nil
        errorCountdown = 0
    }

    private func leaveAfterError() {
        cancelErrorClose()
        dismiss()
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
        if model.isStarting { return }
        if model.request?.isLive == true {
            switch direction {
            case .up: model.zap(by: -1); return
            case .down: model.zap(by: 1); return
            default: break
            }
        }
        // Live TV: left / right only bring up the controls; there is nothing to seek.
        if model.request?.isLive == true {
            showControls(focusScrubber: true)
            return
        }
        switch direction {
        case .left: stepScrub(-seekStep)
        case .right: stepScrub(seekStep)
        default: showControls(focusScrubber: true)
        }
    }

    private func handleMoveOnScrubber(_ direction: MoveCommandDirection) {
        if model.request?.isLive == true { scheduleHide(); return }
        switch direction {
        case .left: stepScrub(-seekStep)
        case .right: stepScrub(seekStep)
        default: break
        }
        scheduleHide()
    }

    /// Accumulates d-pad presses into one seek, committed shortly after the last press.
    private func stepScrub(_ delta: Double) {
        guard model.request?.isLive != true, model.duration.isFinite, model.position.isFinite else { return }
        let base = scrubTarget ?? model.position
        scrubTarget = min(max(0, base + delta), max(model.duration - 1, 0))
        commitScrubSoon()
    }

    private func handleSwipe(_ dx: CGFloat, _ state: UIGestureRecognizer.State) {
        guard model.request?.isLive != true, model.duration > 0, model.duration.isFinite else { return }
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
                        .fill(isFocused ? Color.white : (isSelected ? Color.accentColor.opacity(0.4) : Color.white.opacity(0.08)))
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 18, style: .continuous)
                        .strokeBorder(Color.white.opacity(isSelected && !isFocused ? 0.6 : 0), lineWidth: 2)
                )
                .scaleEffect(configuration.isPressed ? 0.98 : (isFocused ? 1.03 : 1))
                .animation(.easeOut(duration: 0.12), value: isFocused)
        }
    }
}

/// Seek bar + times. Observes only the playback clock, so it is the only part of the player
/// that re-renders as the video plays.
struct TimelineBar: View {
    @ObservedObject var clock: PlaybackClock
    let scrubTarget: Double?
    let isFocused: Bool

    var body: some View {
        let position = scrubTarget ?? clock.position
        let duration = max(clock.duration, 1)
        let trackHeight: CGFloat = isFocused ? 10 : 6
        let knob: CGFloat = isFocused ? 26 : 0
        VStack(alignment: .leading, spacing: 18) {
            GeometryReader { geo in
                let played = geo.size.width * min(1, position / duration)
                let buffered = geo.size.width * min(1, (clock.position + clock.cacheSeconds) / duration)
                ZStack(alignment: .leading) {
                    Capsule().fill(Color.white.opacity(0.25)).frame(height: trackHeight)
                    Capsule().fill(Color.white.opacity(0.4)).frame(width: buffered, height: trackHeight)
                    Capsule().fill(Color.accentColor).frame(width: played, height: trackHeight)
                    Circle().fill(Color.white)
                        .frame(width: knob, height: knob)
                        .offset(x: played - knob / 2)
                }
                .frame(width: geo.size.width, height: geo.size.height)
            }
            .frame(height: 30)
            HStack(spacing: 16) {
                Text(Self.format(position))
                if scrubTarget != nil {
                    Text(String(format: "(%@%@)", position >= clock.position ? "+" : "-", Self.format(abs(position - clock.position))))
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Text("-" + Self.format(max(0, clock.duration - position)))
            }
            .font(.callout.monospacedDigit())
        }
    }

    static func format(_ seconds: Double) -> String {
        guard seconds.isFinite else { return "--:--" }
        let total = Int(seconds.rounded())
        let h = total / 3600, m = (total % 3600) / 60, s = total % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%02d:%02d", m, s)
    }
}

/// "Play now (12s)" countdown, the other clock-driven bit.
struct NextCountdownLabel: View {
    @ObservedObject var clock: PlaybackClock
    var body: some View {
        let remaining = clock.remaining.isFinite ? Int(min(clock.remaining, 86_400)) : 0
        return Text("Play now (\(remaining)s)")
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
                .background {
                    if isFocused { Circle().fill(Color.white) }
                }
                .liquidGlass(in: Circle(), interactive: true)
                .scaleEffect(configuration.isPressed ? 0.94 : (isFocused ? 1.12 : 1))
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

/// A control's icon, plus its name on one line while focused.
private struct PlayerControlLabel: View {
    let title: String
    let icon: String
    let expanded: Bool

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
            if expanded {
                Text(title)
                    .lineLimit(1)
                    .fixedSize()
                    .transition(.opacity.combined(with: .move(edge: .leading)))
            }
        }
        .animation(.easeOut(duration: 0.18), value: expanded)
    }
}


/// Shown while a stream opens: the title's artwork, its logo or name, and a spinner.
private struct StartingCover: View {
    let meta: Meta
    let title: String

    var body: some View {
        ZStack {
            Color.black
            if let backdrop = meta.backdropUrl {
                RemoteImage(url: backdrop)
                    .opacity(0.35)
                    .blur(radius: 8)
            }
            VStack(spacing: 40) {
                if let logo = meta.logo {
                    RemoteImage(url: logo, contentMode: .fit)
                        .frame(maxWidth: 700, maxHeight: 220)
                } else {
                    Text(title)
                        .font(.title2.bold())
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 120)
                }
                ProgressView().scaleEffect(1.6)
            }
        }
        .ignoresSafeArea()
    }
}
