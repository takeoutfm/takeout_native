import QtQuick
import QtQuick.Controls as QQC2
import QtQuick.Layouts
import org.kde.kirigami as Kirigami
import TakeoutFM
import org.kde.ki18n

/**
 * Video's front page, as the Mac's Watch: Continue Watching, Recently Added, New Releases, TV,
 * then the server's recommendation sets, a row each (LIB-3).
 */
AppPage {
    id: page

    title: KI18n.i18n("Watch")

    property var continueWatching: []
    property var recentlyAdded: []
    property var newReleases: []
    property var recentShows: []
    /** [{name, titles}], A–Z within each; none when the server has none. */
    property var recommendations: []
    property int pending: 0
    property var error: null

    function load() {
        error = null
        pending = 5
        const done = (failure) => { pending--; if (failure) error = failure }
        Core.call("continueWatching", {}, (r, f) => { continueWatching = r ?? []; done(f) })
        Core.call("videoRecentlyAdded", {}, (r, f) => { recentlyAdded = r ?? []; done(f) })
        Core.call("videoNewReleases", {}, (r, f) => { newReleases = r ?? []; done(f) })
        Core.call("recentShows", {}, (r, f) => { recentShows = r ?? []; done(f) })
        Core.call("recommendations", {}, (r, f) => { recommendations = r ?? []; done(f) })
    }
    Component.onCompleted: load()

    Connections {
        target: applicationWindow()
        // Back from the player: Continue Watching has moved.
        function onVideoClosed() { Core.call("continueWatching", {}, (r) => page.continueWatching = r ?? []) }
    }

    readonly property bool empty: continueWatching.length + recentlyAdded.length + newReleases.length + recentShows.length === 0

    component Poster: VideoTile {
        required property var modelData
        card: modelData
        width: Kirigami.Units.gridUnit * 8
    }

    ColumnLayout {
        spacing: Kirigami.Units.largeSpacing * 2

        LoadState {
            Layout.alignment: Qt.AlignHCenter
            loading: page.pending > 0 && page.empty
            error: page.empty ? page.error : null
            onRetry: page.load()
        }

        CardRow {
            title: KI18n.i18n("Continue Watching")
            model: page.continueWatching
            delegate: VideoTile {
                required property var modelData
                card: modelData
                wide: true
                width: Kirigami.Units.gridUnit * 14
            }
        }
        CardRow { title: KI18n.i18n("Recently Added"); model: page.recentlyAdded; delegate: Poster {} }
        CardRow { title: KI18n.i18n("New Releases"); model: page.newReleases; delegate: Poster {} }
        CardRow { title: KI18n.i18n("TV"); model: page.recentShows; delegate: Poster {} }
        Repeater {
            model: page.recommendations
            delegate: CardRow {
                required property var modelData
                Layout.fillWidth: true
                title: modelData.name
                model: modelData.titles
                delegate: Poster {}
            }
        }
    }
}
