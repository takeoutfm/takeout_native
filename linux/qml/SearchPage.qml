import QtQuick
import QtQuick.Controls as QQC2
import QtQuick.Layouts
import org.kde.kirigami as Kirigami
import TakeoutFM.Core

/** Music search, in the server's syntax ("artist:Beck", plain words). */
Kirigami.ScrollablePage {
    id: page

    property string query
    property var results: null
    property bool loading: false
    property var error: null

    title: qsTr("Search")

    readonly property bool empty: !!results && results.artists.length === 0 && results.albums.length === 0
        && results.tracks.length === 0 && results.stations.length === 0

    function load() {
        if (query.length === 0) return
        loading = true
        error = null
        Core.call("search", {query: query}, (result, failure) => {
            loading = false
            error = failure
            results = result
        })
    }
    onQueryChanged: load() // also for the query the page is opened with

    header: QQC2.Control {
        padding: Kirigami.Units.smallSpacing
        contentItem: Kirigami.SearchField {
            text: page.query
            placeholderText: qsTr("Search music")
            onAccepted: page.query = text.trim()
            Component.onCompleted: forceActiveFocus()
        }
    }

    ColumnLayout {
        id: content
        spacing: Kirigami.Units.largeSpacing

        LoadState {
            Layout.alignment: Qt.AlignHCenter
            loading: page.loading
            error: page.error
            onRetry: page.load()
        }
        Kirigami.PlaceholderMessage {
            Layout.fillWidth: true
            visible: page.empty && !page.loading
            icon.name: "edit-find"
            text: qsTr("Nothing found for “%1”").arg(page.query)
        }

        SectionHeading { text: qsTr("Artists"); visible: (page.results?.artists.length ?? 0) > 0 }
        GridLayout {
            Layout.fillWidth: true
            columns: Math.max(1, Math.floor(width / (Kirigami.Units.gridUnit * 16)))
            columnSpacing: 0
            rowSpacing: 0
            Repeater {
                model: page.results?.artists ?? []
                ArtistRow {
                    required property var modelData
                    artist: modelData
                }
            }
        }

        AlbumFlow { title: qsTr("Albums"); albums: page.results?.albums ?? [] }

        SectionHeading { text: qsTr("Tracks"); visible: (page.results?.tracks.length ?? 0) > 0 }
        Repeater {
            model: page.results?.tracks ?? []
            TrackRow {
                required property var modelData
                title: modelData.title
                subtitle: [modelData.artist, modelData.album].join(" · ")
                imageUri: modelData.coverUri
                favorite: Core.favoriteTracks.includes(modelData.etag)
            }
        }

        SectionHeading { text: qsTr("Radio"); visible: (page.results?.stations.length ?? 0) > 0 }
        Flow {
            id: stations
            Layout.fillWidth: true
            readonly property int columns: Math.max(1, Math.floor(content.width / (Kirigami.Units.gridUnit * 8)))
            Repeater {
                model: page.results?.stations ?? []
                StationTile {
                    required property var modelData
                    station: modelData
                    width: content.width / stations.columns
                }
            }
        }
    }
}
