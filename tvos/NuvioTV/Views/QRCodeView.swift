import CoreImage.CIFilterBuiltins
import SwiftUI

/// Renders a QR code for device sign-in URLs (Trakt, Torbox, Premiumize, Simkl…).
struct QRCodeView: View {
    let text: String
    var size: CGFloat = 320

    var body: some View {
        if let image = Self.makeImage(text) {
            Image(uiImage: image)
                .interpolation(.none)
                .resizable()
                .frame(width: size, height: size)
                .padding(16)
                .background(Color.white, in: RoundedRectangle(cornerRadius: 16))
        }
    }

    private static func makeImage(_ text: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 10, y: 10)),
              let cgImage = CIContext().createCGImage(output, from: output.extent) else { return nil }
        return UIImage(cgImage: cgImage)
    }
}

/// Shows a device code + QR and polls until the user approves on their phone.
struct DeviceCodeSheet: View {
    let title: String
    let code: String
    let url: String
    let intervalSeconds: Int
    let poll: () async throws -> Bool
    let onDone: (Bool) -> Void

    @State private var status = "Waiting for approval…"

    var body: some View {
        HStack(spacing: 80) {
            QRCodeView(text: url)
            VStack(alignment: .leading, spacing: 24) {
                Text(title).font(.title2).bold()
                Text("On your phone, open").foregroundStyle(.secondary)
                Text(url).font(.headline)
                Text("and enter").foregroundStyle(.secondary)
                Text(code).font(.system(size: 64, weight: .bold, design: .monospaced))
                Text(status).foregroundStyle(.secondary)
                Button("Cancel") { onDone(false) }
            }
        }
        .padding(80)
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: UInt64(max(intervalSeconds, 3)) * 1_000_000_000)
                do {
                    if try await poll() {
                        onDone(true)
                        return
                    }
                } catch {
                    status = error.localizedDescription
                }
            }
        }
    }
}
