import AppKit
import SwiftUI

/**
 * Float on top, the Mac's stand-in for picture-in-picture (VIDEO-9, LIVE-13): the video or Live
 * window becomes a small, title-less window in a screen corner that stays above other apps, on
 * every desktop and over full-screen apps, and keeps the video's shape as it's resized. Toggled
 * again, it returns to its size and place. System picture-in-picture waits for VLCKit 4, which
 * has it; VLCKit 3 draws into its own view, which Apple's picture-in-picture can't take.
 */
@MainActor
final class FloatingWindow: ObservableObject {
    @Published private(set) var isFloating = false
    /// The window this view is in (set by `FloatingWindowReader`).
    weak var window: NSWindow?

    private struct Saved {
        let frame: NSRect
        let level: NSWindow.Level
        let behavior: NSWindow.CollectionBehavior
        let titleVisibility: NSWindow.TitleVisibility
        let transparentTitlebar: Bool
        let movableByBackground: Bool
        let aspect: NSSize
    }
    private var saved: Saved?

    /** The floating window's starting width; its height follows the video's 16:9 shape. */
    private static let width: CGFloat = 400
    private static let margin: CGFloat = 20

    func toggle() {
        if isFloating { restore() } else { float() }
    }

    /**
     * macOS keeps a window's last size: one closed or killed while floating comes back at the
     * floating size, but not floating, with the full bar squeezed in. Such a window opens at
     * the usual size instead, centred.
     */
    func openedAtFloatingSize() {
        guard let window, !isFloating, let screen = window.screen ?? NSScreen.main else { return }
        let content = window.contentRect(forFrameRect: window.frame).size
        guard content.width < 600 else { return }
        let visible = screen.visibleFrame
        let width = min(1280, visible.width - 40)
        let size = NSSize(width: width, height: width * 9 / 16)
        window.setContentSize(size)
        window.center()
    }

    private func float() {
        guard let window, let screen = window.screen ?? NSScreen.main else { return }
        // Full screen first leaves full screen; floating a full-screen window isn't possible.
        if window.styleMask.contains(.fullScreen) {
            window.toggleFullScreen(nil)
            return
        }
        saved = Saved(
            frame: window.frame, level: window.level, behavior: window.collectionBehavior,
            titleVisibility: window.titleVisibility, transparentTitlebar: window.titlebarAppearsTransparent,
            movableByBackground: window.isMovableByWindowBackground, aspect: window.contentAspectRatio
        )
        window.level = .floating
        window.collectionBehavior.formUnion([.canJoinAllSpaces, .fullScreenAuxiliary])
        window.collectionBehavior.remove(.fullScreenPrimary)
        window.titleVisibility = .hidden
        window.titlebarAppearsTransparent = true
        window.isMovableByWindowBackground = true
        window.contentAspectRatio = NSSize(width: 16, height: 9)
        // The bottom-right corner of the screen it's on, above the Dock.
        let size = NSSize(width: Self.width, height: Self.width * 9 / 16)
        let visible = screen.visibleFrame
        let frame = window.frameRect(forContentRect: NSRect(
            x: visible.maxX - size.width - Self.margin, y: visible.minY + Self.margin,
            width: size.width, height: size.height
        ))
        window.setFrame(frame, display: true, animate: true)
        isFloating = true
    }

    private func restore() {
        guard let window, let saved else { isFloating = false; return }
        window.level = saved.level
        window.collectionBehavior = saved.behavior
        window.titleVisibility = saved.titleVisibility
        window.titlebarAppearsTransparent = saved.transparentTitlebar
        window.isMovableByWindowBackground = saved.movableByBackground
        window.contentAspectRatio = saved.aspect
        window.setFrame(saved.frame, display: true, animate: true)
        self.saved = nil
        isFloating = false
    }
}

/** Hands [floating] the NSWindow this view ends up in. */
struct FloatingWindowReader: NSViewRepresentable {
    let floating: FloatingWindow

    func makeNSView(context: Context) -> NSView { Reader(floating: floating) }
    func updateNSView(_ nsView: NSView, context: Context) {}

    private final class Reader: NSView {
        let floating: FloatingWindow
        init(floating: FloatingWindow) {
            self.floating = floating
            super.init(frame: .zero)
        }
        required init?(coder: NSCoder) { fatalError("not used") }
        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            guard let window else { return }
            MainActor.assumeIsolated {
                floating.window = window
                floating.openedAtFloatingSize()
            }
        }
    }
}

/** The bar while floating: back 10 s, play/pause, ahead 10 s (none for live), and Return. */
struct FloatingControls: View {
    let isPlaying: Bool
    let togglePlay: () -> Void
    var jump: ((Int32) -> Void)?
    let restore: () -> Void

    var body: some View {
        HStack(spacing: 16) {
            if let jump {
                Button { jump(-10) } label: { Image(systemName: "gobackward.10") }
            }
            Button(action: togglePlay) {
                Image(systemName: isPlaying ? "pause.fill" : "play.fill").font(.title2)
            }
            if let jump {
                Button { jump(10) } label: { Image(systemName: "goforward.10") }
            }
            Spacer()
            Button(action: restore) { Image(systemName: "pip.exit") }
                .help("Return to the Window (P)")
        }
        .font(.title3)
        .buttonStyle(.borderless)
        .foregroundStyle(.white)
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(LinearGradient(colors: [.clear, .black.opacity(0.75)], startPoint: .top, endPoint: .bottom))
    }
}
