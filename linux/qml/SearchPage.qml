import QtQuick
import QtQuick.Controls as QQC2
import QtQuick.Layouts
import org.kde.kirigami as Kirigami
import TakeoutFM
import org.kde.ki18n

/** Search, in the server's syntax ("artist:Beck", plain words): music, then movies and TV. */
AppPage {
    id: page

    property string query
    property var results: null
    /** Movies, shows and episodes, when the library has video. */
    property var video: null
    property bool loading: false
    property var error: null

    // The query is typed in the sidebar's search field.
    title: query

    readonly property bool empty: !!results && results.artists.length === 0 && results.albums.length === 0
        && results.tracks.length === 0 && results.stations.length === 0
        && (video?.movies.length ?? 0) === 0 && (video?.shows.length ?? 0) === 0 && (video?.episodes.length ?? 0) === 0

    function load() {
        if (query.length === 0) return
        loading = true
        error = null
        Core.call("search", {query: query}, (result, failure) => {
            loading = false
            error = failure
            results = result
        })
        video = null
        if (Core.library.hasMovies || Core.library.hasShows)
            Core.call("videoSearch", {query: query}, (result) => video = result)
    }
    onQueryChanged: load() // also for the query the page is opened with

    component Poster: VideoTile {
        required property var modelData
        card: modelData
        width: Kirigami.Units.gridUnit * 8
    }

    ColumnLayout {
        spacing: Kirigami.Units.largeSpacing * 2

        LoadState {
            Layout.alignment: Qt.AlignHCenter
            loading: page.loading
            error: page.error
            onRetry: page.load()
        }
        Kirigami.PlaceholderMessage {
            Layout.fillWidth: true
            Layout.topMargin: Kirigami.Units.gridUnit * 4
            visible: page.empty && !page.loading
            icon.name: "edit-find"
            text: KI18n.i18n("Nothing found for “%1”", page.query)
        }

        CardRow {
            title: KI18n.i18n("Artists")
            model: page.results?.artists ?? []
            delegate: ArtistCard { required property var modelData; artist: modelData }
        }
        CardRow {
            title: KI18n.i18n("Albums")
            model: page.results?.albums ?? []
            delegate: AlbumTile {
                required property var modelData
                album: modelData
                width: Kirigami.Units.gridUnit * 9
            }
        }
        CardRow {
            title: KI18n.i18n("Radio")
            model: page.results?.stations ?? []
            delegate: StationTile {
                required property var modelData
                station: modelData
                width: Kirigami.Units.gridUnit * 8
            }
        }
        CardRow { title: KI18n.i18n("Movies"); model: page.video?.movies ?? []; delegate: Poster {} }
        CardRow { title: KI18n.i18n("TV Shows"); model: page.video?.shows ?? []; delegate: Poster {} }
        CardRow {
            title: KI18n.i18n("Episodes")
            model: page.video?.episodes ?? []
            delegate: VideoTile {
                required property var modelData
                card: modelData
                wide: true
                width: Kirigami.Units.gridUnit * 14
            }
        }

        ColumnLayout {
            visible: (page.results?.tracks.length ?? 0) > 0
            spacing: 0
            SectionHeading { text: KI18n.i18n("Tracks") }
            Repeater {
                model: page.results?.tracks ?? []
                TrackRow {
                    required property var modelData
                    required property int index
                    // The results, from this one on.
                    onClicked: applicationWindow().playCall("tracksQueue", {title: page.query, tracks: page.results.tracks, startIndex: index})
                    title: modelData.title
                    subtitle: [modelData.artist, modelData.album].join(" · ")
                    imageUri: modelData.coverUri
                    etag: modelData.etag
                    favorite: Core.favoriteTracks.includes(modelData.etag)
                }
            }
        }
    }
}
