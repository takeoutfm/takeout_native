import QtQuick
import QtQuick.Controls as QQC2
import org.kde.kirigami as Kirigami
import TakeoutFM
import org.kde.ki18n

/** Every artist, A–Z, with a filter. */
AppPage {
    id: page

    title: KI18n.i18n("Artists")

    property var artists: []
    property bool loading: false
    property var error: null

    readonly property var shown: {
        const words = filter.text.trim().toLowerCase()
        return words.length === 0 ? artists : artists.filter(a => a.name.toLowerCase().includes(words))
    }

    function load() {
        loading = true
        error = null
        Core.call("artists", {}, (result, failure) => {
            loading = false
            error = failure
            artists = result ?? []
        })
    }
    Component.onCompleted: load()

    header: QQC2.Control {
        padding: Kirigami.Units.smallSpacing
        contentItem: Kirigami.SearchField {
            id: filter
            placeholderText: KI18n.i18n("Filter artists")
        }
    }

    ListView {
        model: page.shown
        reuseItems: true
        // No row is current (or looks selected) until the arrow keys pick one; the list is
        // given focus when the page opens, and a new model would make the first row current.
        currentIndex: -1
        onCountChanged: currentIndex = -1
        keyNavigationEnabled: true
        delegate: ArtistRow {
            required property var modelData
            artist: modelData
            // Only the current row holds the list's focus (a reset leaves it on the first).
            focus: ListView.isCurrentItem
        }

        LoadState {
            anchors.centerIn: parent
            loading: page.loading
            error: page.error
            onRetry: page.load()
        }
    }
}
