import SwiftUI

/**
 * One line of text that, when it's too wide, waits a moment, then slides sideways with a second
 * copy following, and repeats (Now Playing's title, as Android's basicMarquee). Text that fits
 * sits still, placed by [alignment]. Starts over when the text or the width changes. With Reduce
 * Motion on it stays still and ends in "…".
 */
struct MarqueeText: View {
    let text: String
    let font: Font
    var alignment: Alignment = .leading

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /** The text's natural width on one line. */
    @State private var textWidth: CGFloat = 0
    @State private var offset: CGFloat = 0

    private let gap: CGFloat = 48
    /** Points a second, about Compose's default. */
    private let speed: CGFloat = 30
    private let pause: Duration = .milliseconds(1500)

    var body: some View {
        // The hidden line sets the height and takes the width offered; the visible text is
        // drawn over it.
        label
            .lineLimit(1)
            .hidden()
            .frame(maxWidth: .infinity, alignment: alignment)
            .overlay {
                GeometryReader { geo in
                    let width = geo.size.width
                    if textWidth > width && !reduceMotion {
                        HStack(spacing: gap) {
                            label
                            label
                        }
                        .fixedSize()
                        .offset(x: offset)
                        .frame(width: width, alignment: .leading)
                        .task(id: "\(text)|\(textWidth)|\(width)") { await slide() }
                    } else {
                        label
                            .lineLimit(1)
                            .truncationMode(.tail)
                            .frame(width: width, alignment: alignment)
                    }
                }
                .clipped()
            }
            .background {
                label
                    .fixedSize()
                    .hidden()
                    .background(GeometryReader { g in
                        Color.clear.preference(key: TextWidthKey.self, value: g.size.width)
                    })
            }
            .onPreferenceChange(TextWidthKey.self) { textWidth = $0 }
    }

    private var label: Text { Text(text).font(font) }

    /** Wait, slide one copy's width (where the second copy looks like the start), repeat. */
    private func slide() async {
        let distance = textWidth + gap
        let seconds = Double(distance / speed)
        while !Task.isCancelled {
            var jump = Transaction()
            jump.disablesAnimations = true
            withTransaction(jump) { offset = 0 }
            try? await Task.sleep(for: pause)
            if Task.isCancelled { break }
            withAnimation(.linear(duration: seconds)) { offset = -distance }
            try? await Task.sleep(for: .seconds(seconds))
        }
        offset = 0
    }
}

private struct TextWidthKey: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}
