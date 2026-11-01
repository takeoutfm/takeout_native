import QtQuick
import QtQuick.Controls as QQC2
import QtQuick.Layouts
import org.kde.kirigami as Kirigami
import TakeoutFM.Core

/** Music Home: Recently Added or New Releases, as the phone's media bar. */
Kirigami.ScrollablePage {
    id: page

    title: qsTr("Home")

    property int list: 0
    property var albums: []
    property bool loading: false
    property var error: null

    readonly property var calls: ["recentlyAdded", "newReleases"]

    function load() {
        loading = true
        error = null
        Core.call(calls[list], {}, (result, failure) => {
            loading = false
            error = failure
            albums = result ?? []
        })
    }
    onListChanged: load()
    Component.onCompleted: load()

    actions: [
        Kirigami.Action {
            text: qsTr("Recently Added")
            checkable: true
            checked: page.list === 0
            onTriggered: page.list = 0
        },
        Kirigami.Action {
            text: qsTr("New Releases")
            checkable: true
            checked: page.list === 1
            onTriggered: page.list = 1
        },
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
