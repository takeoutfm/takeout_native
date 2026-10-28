import AVKit
import CoreImage
import CoreImage.CIFilterBuiltins
import SwiftUI
#if os(macOS)
import AppKit
typealias PlatformImage = NSImage
#else
import UIKit
typealias PlatformImage = UIImage
#endif

/** The playing item's cover, loaded once: for the lock screen / Control Center, and its colour for Now Playing. */
struct LoadedArtwork {
    let url: String
    let image: PlatformImage
    /** The cover's average colour, for Now Playing's background. */
    let color: Color?

    static func load(_ url: String) async -> LoadedArtwork? {
        guard let u = URL(string: url), !url.isEmpty,
              let (data, _) = try? await URLSession.shared.data(from: u),
              let image = PlatformImage(data: data) else { return nil }
        return LoadedArtwork(url: url, image: image, color: averageColor(data))
    }

    private static func averageColor(_ data: Data) -> Color? {
        guard let input = CIImage(data: data) else { return nil }
        let filter = CIFilter.areaAverage()
        filter.inputImage = input
        filter.extent = input.extent
        guard let output = filter.outputImage else { return nil }
        var rgba = [UInt8](repeating: 0, count: 4)
        CIContext(options: [.workingColorSpace: NSNull()]).render(
            output, toBitmap: &rgba, rowBytes: 4, bounds: CGRect(x: 0, y: 0, width: 1, height: 1),
            format: .RGBA8, colorSpace: nil
        )
        return Color(red: Double(rgba[0]) / 255, green: Double(rgba[1]) / 255, blue: Double(rgba[2]) / 255)
    }
}

/** The system's AirPlay / output picker (AirPlay speakers, Bluetooth, the Mac's outputs). */
struct RoutePicker: View {
    var body: some View {
        RoutePickerView().frame(width: 30, height: 30).help("AirPlay")
    }
}

#if os(macOS)
private struct RoutePickerView: NSViewRepresentable {
    func makeNSView(context: Context) -> AVRoutePickerView {
        let view = AVRoutePickerView()
        view.isRoutePickerButtonBordered = false
        return view
    }
    func updateNSView(_ nsView: AVRoutePickerView, context: Context) {}
}
#else
private struct RoutePickerView: UIViewRepresentable {
    func makeUIView(context: Context) -> AVRoutePickerView {
        let view = AVRoutePickerView()
        view.prioritizesVideoDevices = false
        view.tintColor = .label
        view.activeTintColor = .tintColor
        return view
    }
    func updateUIView(_ uiView: AVRoutePickerView, context: Context) {}
}
#endif
