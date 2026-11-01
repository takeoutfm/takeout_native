import QtQuick
import QtQuick.Controls as QQC2
import QtQuick.Layouts
import org.kde.kirigami as Kirigami
import TakeoutFM.Core

/**
 * A movie, series or episode, as the phone shows it: backdrop, facts, Resume / Start over,
 * description, cast and crew, a series' seasons or an episode's season, and similar titles.
 */
Kirigami.ScrollablePage {
    id: page

    required property string videoId
    /** What the tile already knew, shown while the details load. */
    property var card: null
    property var details: null
    /** Saved progress for what Play starts ({Offset, Duration} seconds), when worth resuming. */
    property var offset: null
    property bool loading: false
    property var error: null
    property int season: 0

    title: details?.title ?? card?.title ?? ""

    readonly property bool isSeries: details?.kind === "Series"
    readonly property var shownSeason: (details?.seasons ?? []).find(s => s.number === season) ?? details?.seasons?.[0]

    function load() {
        loading = true
        error = null
        Core.call("videoDetails", {id: videoId}, (result, failure) => {
            loading = false
            error = failure
            if (!result) return
            details = result
            if (season === 0 && result.seasons.length > 0) {
                // The season Play is in, else the first.
                const target = /^S(\d+)E/.exec(result.playTarget ?? "")
                season = target ? Number(target[1]) : result.seasons[0].number
            }
            loadOffset()
        })
    }

    function loadOffset() {
        const etag = details?.etag ?? ""
        if (!etag) { offset = null; return }
        Core.call("resumeOffset", {etag: etag}, (result) => offset = result)
    }
    Component.onCompleted: load()

    Connections {
        target: applicationWindow()
        // Back from the player: the resume point, and a series' next episode, may have moved.
        function onVideoClosed() { page.isSeries ? page.load() : page.loadOffset() }
    }

    function bytes(n) {
        const KB = 1024, MB = KB * 1024, GB = MB * 1024
        if (n >= GB) return (Math.round(n * 10 / GB) / 10).toFixed(1) + " GB"
        if (n >= MB) return Math.floor(n / MB) + " MB"
        return Math.max(1, Math.floor(n / KB)) + " KB"
    }

    /** "S2E5 · Mar 3, 2014 · TV-14 · 47 min · ★ 8.1/10", as the phone. */
    function facts(d) {
        const runtime = d.kind === "Series" ? qsTr("%1 seasons · %2 episodes").arg(d.seasonCount).arg(d.episodeCount)
            : d.runtimeMinutes > 59 ? qsTr("%1h %2m").arg(Math.floor(d.runtimeMinutes / 60)).arg(String(d.runtimeMinutes % 60).padStart(2, "0"))
            : d.runtimeMinutes > 0 ? qsTr("%1 min").arg(d.runtimeMinutes) : ""
        const aired = d.episode?.airDate ? new Date(d.episode.airDate + "T00:00:00").toLocaleDateString(Qt.locale(), Locale.ShortFormat) : ""
        return [
            d.episode ? `S${d.episode.season}E${d.episode.episode}` : "",
            aired || (d.year !== "0001" ? d.year : ""),
            d.rating,
            d.size > 0 ? bytes(d.size) : "",
            runtime,
            d.voteAverage !== null && d.voteAverage !== undefined ? qsTr("★ %1/10").arg(d.voteAverage.toFixed(1)) : "",
        ].filter(s => s && s.length > 0).join(" · ")
    }

    function openGenre(name) {
        Core.call("genres", {}, (genres) => {
            const genre = (genres ?? []).find(g => g.name === name)
            if (genre) applicationWindow().open("VideoGridPage", {title: name, call: "genre", args: {genreId: genre.id}, field: "movies"})
        })
    }

    actions: [
        Kirigami.Action {
            visible: !!page.details?.favoritePath
            text: page.details?.isFavorite ? qsTr("Unfavorite") : qsTr("Favorite")
            icon.name: page.details?.isFavorite ? "starred-symbolic" : "non-starred-symbolic"
            onTriggered: Core.call("setVideoFavorite", {path: page.details.favoritePath, favorite: !page.details.isFavorite},
                                   (r, error) => { if (!error) page.load() })
        },
        Kirigami.Action {
            visible: (page.details?.trailers.length ?? 0) > 0
            text: qsTr("Trailer")
            icon.name: "media-playback-start"
            onTriggered: Qt.openUrlExternally(page.details.trailers[0].uri)
        }
    ]

    ColumnLayout {
        spacing: Kirigami.Units.largeSpacing

        Image {
            Layout.fillWidth: true
            Layout.preferredHeight: status === Image.Ready ? Math.min(width * 9 / 16, Kirigami.Units.gridUnit * 18) : 0
            visible: status === Image.Ready
            source: page.details?.backdropUri || page.card?.backdropUri || ""
            fillMode: Image.PreserveAspectCrop
            asynchronous: true
        }

        LoadState {
            Layout.alignment: Qt.AlignHCenter
            loading: page.loading && !page.details
            error: page.details ? null : page.error
            onRetry: page.load()
        }

        // An episode's series, as a link.
        QQC2.Label {
            visible: !!page.details?.episode
            text: `<a href="series">${page.details?.episode?.seriesTitle ?? ""}</a>`
            textFormat: Text.StyledText
            onLinkActivated: applicationWindow().open("VideoPage", {videoId: page.details.episode.seriesId})
            HoverHandler { cursorShape: parent.hoveredLink ? Qt.PointingHandCursor : Qt.ArrowCursor }
        }
        Kirigami.Heading {
            Layout.fillWidth: true
            visible: !!page.details
            // An episode's title already names its series and number ("Breaking Bad · S1E3 …").
            text: page.details?.episode?.name ?? page.details?.title ?? ""
            wrapMode: Text.Wrap
        }
        QQC2.Label {
            Layout.fillWidth: true
            visible: text.length > 0
            text: page.details?.tagline ?? ""
            font.italic: true
            wrapMode: Text.Wrap
        }
        QQC2.Label {
            Layout.fillWidth: true
            visible: !!page.details
            text: page.details ? page.facts(page.details) : ""
            opacity: 0.7
            wrapMode: Text.Wrap
        }

        // Resume ("N min left") and Start over, or just Play.
        Flow {
            Layout.fillWidth: true
            visible: !!page.details
            spacing: Kirigami.Units.smallSpacing
            readonly property string target: page.details?.playTarget ? " " + page.details.playTarget : ""

            QQC2.Label {
                visible: page.details?.videoUri === ""
                text: qsTr("The server has no playable file for this title.")
                color: Kirigami.Theme.negativeTextColor
            }
            QQC2.Button {
                visible: page.details?.videoUri !== "" && !!page.offset
                icon.name: "media-playback-start"
                text: qsTr("Resume") + parent.target + (page.offset
                    ? " · " + qsTr("%1 min left").arg(Math.max(1, Math.floor((page.offset.Duration - page.offset.Offset) / 60))) : "")
                onClicked: applicationWindow().playVideo(page.videoId, false)
            }
            QQC2.Button {
                visible: page.details?.videoUri !== "" && !!page.offset
                icon.name: "media-seek-backward"
                text: qsTr("Start over")
                onClicked: applicationWindow().playVideo(page.videoId, true)
            }
            QQC2.Button {
                visible: page.details?.videoUri !== "" && !page.offset
                icon.name: "media-playback-start"
                text: qsTr("Play") + parent.target
                onClicked: applicationWindow().playVideo(page.videoId, false)
            }
        }

        QQC2.Label {
            Layout.fillWidth: true
            visible: text.length > 0
            text: page.details?.description ?? ""
            wrapMode: Text.Wrap
        }

        // A movie's genres.
        Flow {
            Layout.fillWidth: true
            visible: page.details?.kind === "Movie" && page.details.genres.length > 0
            spacing: Kirigami.Units.smallSpacing
            Repeater {
                model: page.details?.genres ?? []
                QQC2.Button {
                    required property string modelData
                    text: modelData
                    flat: true
                    onClicked: page.openGenre(modelData)
                }
            }
        }

        // A series' seasons.
        RowLayout {
            visible: page.isSeries && (page.details?.seasons.length ?? 0) > 0
            SectionHeading { text: qsTr("Episodes") }
            Item { Layout.fillWidth: true }
            QQC2.ComboBox {
                visible: (page.details?.seasons.length ?? 0) > 1
                model: (page.details?.seasons ?? []).map(s => qsTr("Season %1").arg(s.number))
                currentIndex: (page.details?.seasons ?? []).findIndex(s => s.number === page.shownSeason?.number)
                onActivated: (index) => page.season = page.details.seasons[index].number
            }
        }
        Repeater {
            model: page.isSeries ? (page.shownSeason?.episodes ?? []) : []
            EpisodeRow { required property var modelData; episode: modelData }
        }

        // The rest of an episode's season.
        SectionHeading {
            visible: (page.details?.episode?.seasonEpisodes.length ?? 0) > 1
            text: qsTr("Season %1").arg(page.details?.episode?.season ?? 0)
        }
        Repeater {
            model: (page.details?.episode?.seasonEpisodes.length ?? 0) > 1 ? page.details.episode.seasonEpisodes : []
            EpisodeRow {
                required property var modelData
                episode: modelData
                highlighted: modelData.id === page.videoId
            }
        }

        // Cast and crew.
        SectionHeading { text: qsTr("Cast & Crew"); visible: (page.details?.cast.length ?? 0) > 0 }
        Flow {
            Layout.fillWidth: true
            visible: (page.details?.cast.length ?? 0) > 0
            spacing: Kirigami.Units.smallSpacing
            Repeater {
                model: (page.details?.cast ?? []).slice(0, 18)
                PersonTile { required property var modelData; credit: modelData }
            }
        }
        Repeater {
            model: page.details ? [
                [qsTr("Directed by"), page.details.directors],
                [qsTr("Written by"), page.details.writers],
                [qsTr("Music by"), page.details.composers],
            ].filter(r => r[1].length > 0) : []
            QQC2.Label {
                required property var modelData
                Layout.fillWidth: true
                text: `<b>${modelData[0]}</b> ${modelData[1].join(", ")}`
                textFormat: Text.StyledText
                wrapMode: Text.Wrap
            }
        }

        VideoFlow { title: qsTr("More Like This"); cards: page.details?.similar ?? []; limit: 12 }
    }
}
