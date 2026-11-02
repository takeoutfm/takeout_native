import QtQuick
import org.kde.kirigami as Kirigami
import TakeoutFM

/**
 * A scrollable page with the Now Playing bar as its footer (Kirigami's Page.footer), shown
 * while something is queued. The window's own footer can't hold it: Kirigami moves that left
 * by the sidebar's width.
 */
Kirigami.ScrollablePage {
    footer: MiniPlayer {
        visible: Player.hasQueue
        onOpen: applicationWindow().showNowPlaying()
    }
}
