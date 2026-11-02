import QtQuick
import QtQuick.Controls as QQC2
import org.kde.kirigami as Kirigami
import TakeoutFM

/**
 * A grid of posters from shared call [call] with [args]: all movies, favorites, a genre. The
 * cards are the result, or its [field]; [prefix] keeps only ids starting with it ("m-", "s-").
 */
AppPage {
    id: page

    required property string call
    property var args: ({})
    property string field: ""
    property string prefix: ""
    property var cards: []
    property bool loading: false
    property var error: null

    function load() {
        loading = true
        error = null
        Core.call(call, args, (result, failure) => {
            loading = false
            error = failure
            const list = (field ? result?.[field] : result) ?? []
            cards = prefix ? list.filter(c => c.id.startsWith(prefix)) : list
        })
    }
    Component.onCompleted: load()

    GridView {
        id: grid
        // No tile is current (or looks selected) until the arrow keys pick one; the grid is
        // given focus when the page opens, and a new model would make the first tile current.
        currentIndex: -1
        onCountChanged: currentIndex = -1
        keyNavigationEnabled: true
        readonly property int columns: Math.max(1, Math.floor(width / (Kirigami.Units.gridUnit * 8)))
        cellWidth: width / columns
        cellHeight: cellWidth * 3 / 2 + Kirigami.Units.gridUnit * 2.5
        model: page.cards
        delegate: VideoTile {
            required property var modelData
            width: grid.cellWidth
            card: modelData
            // Only the current tile holds the grid's focus (a reset leaves it on the first).
            focus: GridView.isCurrentItem
        }

        LoadState {
            anchors.centerIn: parent
            loading: page.loading && page.cards.length === 0
            error: page.error
            onRetry: page.load()
        }
        Kirigami.PlaceholderMessage {
            anchors.centerIn: parent
            width: parent.width - Kirigami.Units.gridUnit * 4
            visible: !page.loading && !page.error && page.cards.length === 0
            icon.name: "video-x-generic"
            text: qsTr("Nothing here")
        }
    }
}
