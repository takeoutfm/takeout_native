import QtQuick
import QtQuick.Controls as QQC2
import org.kde.kirigami as Kirigami
import TakeoutFM.Core

/** Podcast series: the subscribed ones, or all the server has. */
Kirigami.ScrollablePage {
    id: page

    title: qsTr("Podcasts")

    property bool subscribedOnly: true
    property var series: []
    property bool loading: false
    property var error: null

    function load() {
        loading = true
        error = null
        Core.call("podcasts", {subscribedOnly: subscribedOnly}, (result, failure) => {
            loading = false
            error = failure
            series = result ?? []
        })
    }
    onSubscribedOnlyChanged: load()
    Component.onCompleted: load()

    actions: [
        Kirigami.Action {
            text: qsTr("Subscribed")
            checkable: true
            checked: page.subscribedOnly
            onTriggered: page.subscribedOnly = true
        },
        Kirigami.Action {
            text: qsTr("All")
            checkable: true
            checked: !page.subscribedOnly
            onTriggered: page.subscribedOnly = false
        }
    ]

    GridView {
        id: grid
        currentIndex: -1
        readonly property int columns: Math.max(1, Math.floor(width / (Kirigami.Units.gridUnit * 10)))
        cellWidth: width / columns
        cellHeight: cellWidth + Kirigami.Units.gridUnit * 3
        model: page.series
        // The grid focuses its current item; a wrapper takes that, so no tile looks selected.
        delegate: Item {
            id: cell
            required property var modelData
            width: grid.cellWidth
            height: grid.cellHeight
            AlbumTile {
                readonly property var modelData: cell.modelData
                width: parent.width
                album: ({name: modelData.title, artist: modelData.author, coverUri: modelData.imageUri})
                opensAlbum: false
                onClicked: applicationWindow().open("PodcastPage", {seriesId: modelData.id, series: modelData})
            }
        }

        LoadState {
            anchors.centerIn: parent
            loading: page.loading && page.series.length === 0
            error: page.error
            onRetry: page.load()
        }
        Kirigami.PlaceholderMessage {
            anchors.centerIn: parent
            width: parent.width - Kirigami.Units.gridUnit * 4
            visible: !page.loading && !page.error && page.series.length === 0
            icon.name: "podcast-symbolic"
            text: page.subscribedOnly ? qsTr("No subscriptions") : qsTr("No podcasts")
        }
    }
}
