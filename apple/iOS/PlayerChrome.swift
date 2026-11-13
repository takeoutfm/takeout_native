import SwiftUI

/**
 * The video and live players' controls on iPhone and iPad, laid out as Apple's player: a close
 * button alone at the top left, the transport in the middle, and along the bottom the titles
 * (with the audio and subtitle menus at their right) over the slider or the live progress.
 * Both players use it, so they stay alike.
 */
struct PlayerChrome<Center: View, Titles: View, Menus: View, Bar: View>: View {
    let close: () -> Void
    @ViewBuilder let center: () -> Center
    @ViewBuilder let titles: () -> Titles
    @ViewBuilder let menus: () -> Menus
    @ViewBuilder let bar: () -> Bar

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Button(action: close) {
                    Image(systemName: "xmark")
                        .font(.body.weight(.semibold))
                        .frame(width: 36, height: 36)
                        .background(.ultraThinMaterial, in: Circle())
                }
                .accessibilityLabel("Close")
                Spacer()
            }
            .padding(.horizontal)
            .padding(.top, 8)
            Spacer()
            center()
            Spacer()
            VStack(alignment: .leading, spacing: 12) {
                HStack(alignment: .bottom, spacing: 20) {
                    titles().frame(maxWidth: .infinity, alignment: .leading)
                    menus().font(.title3)
                }
                bar()
            }
            .padding()
            .background(LinearGradient(colors: [.clear, .black.opacity(0.75)], startPoint: .top, endPoint: .bottom))
        }
        .foregroundStyle(.white)
        .tint(.white)
        .buttonStyle(.plain)
        .environment(\.colorScheme, .dark)
    }
}
