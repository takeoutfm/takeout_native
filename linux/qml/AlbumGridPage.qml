import QtQuick
import QtQuick.Controls as QQC2
import QtQuick.Layouts
import org.kde.kirigami as Kirigami
import TakeoutFM.Core

/** Albums from shared call [call] as a grid: Recently Added, New Releases (the Mac's pages). */
Kirigami.ScrollablePage {
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
        currentIndex: -1
        readonly property int columns: Math.max(1, Math.floor(width / (Kirigami.Units.gridUnit * 10)))
        cellWidth: width / columns
        cellHeight: cellWidth + Kirigami.Units.gridUnit * 3
        model: page.albums
        // The grid focuses its current item; a wrapper takes that, so no tile looks selected.
        delegate: Item {
            id: cell
            required property var modelData
            width: grid.cellWidth
            height: grid.cellHeight
            AlbumTile {
                readonly property var modelData: cell.modelData
                width: parent.width
                album: modelData
            }
        }

        LoadState {
            anchors.centerIn: parent
            loading: page.loading && page.albums.length === 0
            error: page.error
            onRetry: page.load()
        }
    }
}
