import SwiftUI

/// Liquid Glass (tvOS 26+) with a frosted-material fallback on older tvOS.
extension View {
    /// Glass behind the view in [shape]. [tint] darkens / colours the glass (e.g. for text over a
    /// bright video); [interactive] makes it react to focus and presses like system glass controls.
    @ViewBuilder
    func liquidGlass<S: Shape>(in shape: S, tint: Color? = nil, interactive: Bool = false) -> some View {
        if #available(tvOS 26.0, *) {
            self.glassEffect(makeGlass(tint: tint, interactive: interactive), in: shape)
        } else {
            self.background {
                ZStack {
                    shape.fill(.ultraThinMaterial)
                    if let tint { shape.fill(tint) }
                    shape.stroke(Color.white.opacity(0.18), lineWidth: 1)
                }
            }
        }
    }
}

@available(tvOS 26.0, *)
private func makeGlass(tint: Color?, interactive: Bool) -> Glass {
    var glass = Glass.regular
    if let tint { glass = glass.tint(tint) }
    if interactive { glass = glass.interactive() }
    return glass
}

/// Glass capsule button: clear glass at rest, solid white with dark text when focused (the tvOS
/// focus convention, readable over any frame of video).
struct GlassCapsuleButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        GlassCapsule(configuration: configuration)
    }

    private struct GlassCapsule: View {
        let configuration: ButtonStyle.Configuration
        @Environment(\.isFocused) private var isFocused

        var body: some View {
            configuration.label
                .font(.callout.weight(.semibold))
                .foregroundStyle(isFocused ? Color.black : Color.white)
                .padding(.horizontal, 28)
                .padding(.vertical, 16)
                .background {
                    if isFocused { Capsule().fill(Color.white) }
                }
                .liquidGlass(in: Capsule(), interactive: true)
                .scaleEffect(configuration.isPressed ? 0.95 : (isFocused ? 1.08 : 1))
                .shadow(color: .black.opacity(isFocused ? 0.35 : 0), radius: 14, y: 6)
                .animation(.easeOut(duration: 0.15), value: isFocused)
        }
    }
}
