import QtQuick
import QtQuick.Controls as QQC2
import QtQuick.Layouts
import org.kde.kirigami as Kirigami
import TakeoutFM

/** Albums from shared call [call] as a grid: Recently Added, New Releases (the Mac's pages). */
AppPage {
    id: page

    required property string call
    property var albums: []
    property bool loading: false
    property var error: null

    function load() {
        loading = true
        error = null
        Core.call(call, {}, (result, failure) => {
            loading = false
            error = failure
            albums = result ?? []
        })
    }
    Component.onCompleted: load()

    actions: [
        Kirigami.Action {
            text: qsTr("Reload")
            icon.name: "view-refresh"
            displayHint: Kirigami.DisplayHint.AlwaysHide
            onTriggered: {
                Core.call("refreshLibrary", {maxAgeMs: 0})
                page.load()
            }
        }
    ]

    GridView {
        id: grid
        // No tile is current (or looks selected) until the arrow keys pick one; the grid is
        // given focus when the page opens, and a new model would make the first tile current.
        currentIndex: -1
        onCountChanged: currentIndex = -1
        keyNavigationEnabled: true
        readonly property int columns: Math.max(1, Math.floor(width / (Kirigami.Units.gridUnit * 10)))
        cellWidth: width / columns
        cellHeight: cellWidth + Kirigami.Units.gridUnit * 3
        model: page.albums
        delegate: AlbumTile {
            required property var modelData
            width: grid.cellWidth
            album: modelData
            // Only the current tile holds the grid's focus (a reset leaves it on the first).
            focus: GridView.isCurrentItem
        }

        LoadState {
            anchors.centerIn: parent
            loading: page.loading && page.albums.length === 0
            error: page.error
            onRetry: page.load()
        }
    }
}
