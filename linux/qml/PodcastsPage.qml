import QtQuick
import QtQuick.Controls as QQC2
import org.kde.kirigami as Kirigami
import TakeoutFM
import org.kde.ki18n

/** Podcast series: the subscribed ones, or all the server has. */
AppPage {
    id: page

    title: KI18n.i18n("Podcasts")

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
            text: KI18n.i18n("Subscribed")
            checkable: true
            checked: page.subscribedOnly
            onTriggered: page.subscribedOnly = true
        },
        Kirigami.Action {
            text: KI18n.i18n("All")
            checkable: true
            checked: !page.subscribedOnly
            onTriggered: page.subscribedOnly = false
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
        model: page.series
        delegate: AlbumTile {
            required property var modelData
            width: grid.cellWidth
            album: ({name: modelData.title, artist: modelData.author, coverUri: modelData.imageUri})
            opensAlbum: false
            onClicked: applicationWindow().open("PodcastPage", {seriesId: modelData.id, series: modelData})
            // Only the current tile holds the grid's focus (a reset leaves it on the first).
            focus: GridView.isCurrentItem
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
            text: page.subscribedOnly ? KI18n.i18n("No subscriptions") : KI18n.i18n("No podcasts")
        }
    }
}
