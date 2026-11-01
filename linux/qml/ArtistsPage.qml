import QtQuick
import QtQuick.Controls as QQC2
import org.kde.kirigami as Kirigami
import TakeoutFM.Core

/** Every artist, A–Z, with a filter. */
Kirigami.ScrollablePage {
    id: page

    title: qsTr("Artists")

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
            placeholderText: qsTr("Filter artists")
        }
    }

    ListView {
        model: page.shown
        reuseItems: true
        delegate: ArtistRow {
            required property var modelData
            artist: modelData
        }

        LoadState {
            anchors.centerIn: parent
            loading: page.loading
            error: page.error
            onRetry: page.load()
        }
    }
}
