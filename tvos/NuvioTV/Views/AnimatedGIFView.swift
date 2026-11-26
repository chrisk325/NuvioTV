import SwiftUI
import UIKit
import ImageIO

/// Plays an animated GIF (frames decoded with ImageIO, shown in a UIImageView, which animates).
/// Frames are downsampled to [maxPixel] and capped, so a large GIF stays light; it's loaded only
/// while this view is on screen and released when it goes away.
struct AnimatedGIFView: View {
    let url: String
    var maxPixel: CGFloat = 640
    /// Shown until the GIF has loaded (and if it can't be).
    var placeholder: AnyView = AnyView(Color.clear)

    @State private var image: UIImage?

    var body: some View {
        ZStack {
            if let image {
                AnimatedImageView(image: image)
            } else {
                placeholder
            }
        }
        .task(id: url) {
            image = await GIFDecoder.load(url: url, maxPixel: maxPixel)
        }
        .onDisappear { image = nil }
    }
}

private struct AnimatedImageView: UIViewRepresentable {
    let image: UIImage

    func makeUIView(context: Context) -> UIImageView {
        let view = UIImageView()
        view.contentMode = .scaleAspectFill
        view.clipsToBounds = true
        view.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        view.setContentCompressionResistancePriority(.defaultLow, for: .vertical)
        view.setContentHuggingPriority(.defaultLow, for: .horizontal)
        view.setContentHuggingPriority(.defaultLow, for: .vertical)
        return view
    }

    func updateUIView(_ view: UIImageView, context: Context) {
        if view.image !== image {
            view.image = image
            view.startAnimating()
        }
    }
}

enum GIFDecoder {
    /// At most this many frames are kept (longer GIFs are sampled evenly).
    private static let maxFrames = 120

    static func load(url: String, maxPixel: CGFloat) async -> UIImage? {
        // Through the image loader: kept in the image cache (Settings > About clears it).
        guard let data = await ImageLoader.shared.fileData(url), !Task.isCancelled else { return nil }
        return await Task.detached(priority: .userInitiated) { decode(data, maxPixel: maxPixel) }.value
    }

    static func decode(_ data: Data, maxPixel: CGFloat) -> UIImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        let count = CGImageSourceGetCount(source)
        guard count > 0 else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixel,
            kCGImageSourceShouldCacheImmediately: true,
        ]
        if count == 1 {
            return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary).map { UIImage(cgImage: $0) }
        }
        let step = max(1, Int((Double(count) / Double(maxFrames)).rounded(.up)))
        var frames: [UIImage] = []
        var total: Double = 0
        var index = 0
        while index < count {
            var delay: Double = 0
            for i in index..<min(index + step, count) { delay += frameDelay(source, i) }
            if let frame = CGImageSourceCreateThumbnailAtIndex(source, index, options as CFDictionary) {
                frames.append(UIImage(cgImage: frame))
                total += delay
            }
            index += step
        }
        guard !frames.isEmpty else { return nil }
        return UIImage.animatedImage(with: frames, duration: total > 0 ? total : Double(frames.count) * 0.1)
    }

    /// A frame's delay, with browsers' rule: under 20 ms counts as 100 ms.
    private static func frameDelay(_ source: CGImageSource, _ index: Int) -> Double {
        guard let props = CGImageSourceCopyPropertiesAtIndex(source, index, nil) as? [CFString: Any],
              let gif = props[kCGImagePropertyGIFDictionary] as? [CFString: Any] else { return 0.1 }
        let delay = (gif[kCGImagePropertyGIFUnclampedDelayTime] as? Double) ?? (gif[kCGImagePropertyGIFDelayTime] as? Double) ?? 0.1
        return delay < 0.02 ? 0.1 : delay
    }
}
