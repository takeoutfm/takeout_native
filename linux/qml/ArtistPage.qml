import QtQuick
import QtQuick.Controls as QQC2
import QtQuick.Layouts
import org.kde.kirigami as Kirigami
import TakeoutFM
import org.kde.ki18n

/** An artist: popular tracks, albums, singles, other releases and similar artists. */
AppPage {
    id: page

    required property string artistId
    /** What the list already knew, shown while the details load. */
    property var artist: null
    property var details: null
    property bool loading: false
    property var error: null

    title: details?.artist.name ?? artist?.name ?? ""

    function load() {
        loading = true
        error = null
        Core.call("artistDetails", {artistId: artistId}, (result, failure) => {
            loading = false
            error = failure
            if (result) details = result
        })
    }
    Component.onCompleted: load()

    actions: [
        Kirigami.Action {
            text: KI18n.i18n("Play")
            icon.name: "media-playback-start"
            onTriggered: applicationWindow().playCall("artistQueue", {artistId: page.artistId, mix: "Popular"})
        },
        Kirigami.Action {
            text: KI18n.i18n("Shuffle")
            icon.name: "media-playlist-shuffle"
            onTriggered: applicationWindow().playCall("artistQueue", {artistId: page.artistId, mix: "Shuffle"})
        },
        Kirigami.Action {
            text: KI18n.i18n("Radio")
            icon.name: "radio"
            onTriggered: applicationWindow().playCall("artistQueue", {artistId: page.artistId, mix: "Radio"})
        },
        Kirigami.Action {
            visible: !!page.details && page.details.artist.arid.length > 0
            text: page.details?.isFavorite ? KI18n.i18n("Unfavorite") : KI18n.i18n("Favorite")
            icon.name: page.details?.isFavorite ? "starred-symbolic" : "non-starred-symbolic"
            onTriggered: {
                const favorite = !page.details.isFavorite
                Core.call("setArtistFavorite", {arid: page.details.artist.arid, favorite: favorite}, (r, error) => {
                    if (!error) page.load()
                })
            }
        }
    ]

    ColumnLayout {
        spacing: Kirigami.Units.largeSpacing

        Image {
            Layout.fillWidth: true
            Layout.preferredHeight: status === Image.Ready ? Math.min(width * 9 / 16, Kirigami.Units.gridUnit * 16) : 0
            source: page.details?.backgroundUri ?? ""
            fillMode: Image.PreserveAspectCrop
            asynchronous: true
            visible: status === Image.Ready
        }

        LoadState {
            Layout.alignment: Qt.AlignHCenter
            loading: page.loading && !page.details
            error: page.details ? null : page.error
            onRetry: page.load()
        }

        SectionHeading {
            text: KI18n.i18n("Popular")
            visible: (page.details?.popular.length ?? 0) > 0
        }
        Repeater {
            model: page.details?.popular.slice(0, 5) ?? []
            TrackRow {
                required property var modelData
                required property int index
                onClicked: applicationWindow().playCall("tracksQueue", {
                    title: page.details.artist.name, tracks: page.details.popular.slice(0, 5), startIndex: index})
                title: modelData.title
                subtitle: modelData.album
                imageUri: modelData.coverUri
                favorite: Core.favoriteTracks.includes(modelData.etag)
            }
        }

        AlbumFlow { title: KI18n.i18n("Albums"); albums: page.details?.albums ?? []; showYear: true }
        AlbumFlow { title: KI18n.i18n("Singles and EPs"); albums: page.details?.singles ?? []; showYear: true }
        AlbumFlow { title: KI18n.i18n("Other releases"); albums: page.details?.otherReleases ?? []; showYear: true }

        SectionHeading {
            text: KI18n.i18n("Similar artists")
            visible: (page.details?.similar.length ?? 0) > 0
        }
        GridLayout {
            Layout.fillWidth: true
            columns: Math.max(1, Math.floor(width / (Kirigami.Units.gridUnit * 16)))
            columnSpacing: 0
            rowSpacing: 0
            Repeater {
                model: page.details?.similar ?? []
                ArtistRow {
                    required property var modelData
                    artist: modelData
                }
            }
        }
    }
}
