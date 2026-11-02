import QtQuick
import QtQuick.Controls as QQC2
import QtQuick.Layouts
import org.kde.kirigami as Kirigami
import TakeoutFM.Core

/** Favorite artists (a row) and tracks, as the Mac's Favorites. */
Kirigami.ScrollablePage {
    id: page

    title: qsTr("Favorites")

    property var favorites: null
    property bool loading: false
    property var error: null

    readonly property var tracks: favorites?.tracks ?? []
    readonly property var etags: tracks.map(t => t.etag).filter(e => e)
    readonly property bool allDownloaded: etags.length > 0 && etags.every(e => Core.downloadsByEtag[e]?.state === "Done")

    function load() {
        loading = true
        error = null
        Core.call("favorites", {}, (result, failure) => {
            loading = false
            error = failure
            if (result) favorites = result
        })
    }
    Component.onCompleted: load()

    // A track favorited or not elsewhere (Now Playing's ♥).
    Connections {
        target: Core
        function onFavoriteTracksChanged() { page.load() }
    }

    ColumnLayout {
        spacing: Kirigami.Units.largeSpacing * 2

        LoadState {
            Layout.alignment: Qt.AlignHCenter
            loading: page.loading && !page.favorites
            error: page.favorites ? null : page.error
            onRetry: page.load()
        }
        Kirigami.PlaceholderMessage {
            Layout.fillWidth: true
            Layout.topMargin: Kirigami.Units.gridUnit * 4
            visible: !!page.favorites && page.favorites.artists.length === 0 && page.tracks.length === 0
            icon.name: "emblem-favorite"
            text: qsTr("No favorites")
        }

        CardRow {
            title: qsTr("Artists")
            model: page.favorites?.artists ?? []
            delegate: ArtistCard {
                required property var modelData
                artist: modelData
            }
        }

        ColumnLayout {
            visible: page.tracks.length > 0
            spacing: 0

            RowLayout {
                Layout.fillWidth: true
                SectionHeading { text: qsTr("Tracks") }
                Item { Layout.fillWidth: true }
                QQC2.Button {
                    icon.name: "media-playback-start"
                    text: qsTr("Play All")
                    onClicked: applicationWindow().playCall("favoritesQueue", {startIndex: 0})
                }
                QQC2.Button {
                    icon.name: page.allDownloaded ? "edit-delete-remove" : "download"
                    text: page.allDownloaded ? qsTr("Remove Download") : qsTr("Download")
                    onClicked: page.allDownloaded ? Core.call("removeDownloads", {etags: page.etags})
                                                  : applicationWindow().downloadCall("favoritesQueue", {startIndex: 0})
                }
            }
            Repeater {
                model: page.tracks
                TrackRow {
                    required property var modelData
                    required property int index
                    title: modelData.title
                    subtitle: [modelData.artist, modelData.album].join(" · ")
                    imageUri: modelData.coverUri
                    etag: modelData.etag
                    onClicked: applicationWindow().playCall("favoritesQueue", {startIndex: index})
                }
            }
        }
    }
}
