import QtQuick
import QtQuick.Controls as QQC2
import QtQuick.Layouts
import org.kde.kirigami as Kirigami
import TakeoutFM
import org.kde.ki18n

/**
 * A podcast episode, as the Mac's and the phone's: its art, title, show and date, Resume or
 * Play, Start Over, Mark as Played, Download; then the show notes (links open in the browser).
 */
AppPage {
    id: page

    required property string seriesId
    required property string episodeId
    property var details: null
    property bool loading: false
    property var error: null

    readonly property int index: (details?.episodes ?? []).findIndex(e => e.id === episodeId)
    readonly property var episode: index >= 0 ? details.episodes[index] : null
    readonly property var series: details?.series ?? null
    readonly property bool started: episode?.progress?.type === "Started"
    readonly property bool played: episode?.progress?.type === "Complete"
    readonly property var download: episode ? Core.downloadsByEtag[episode.eid] : undefined

    title: series?.title ?? ""

    function load() {
        loading = true
        error = null
        Core.call("podcastDetails", {seriesId: seriesId}, (result, failure) => {
            loading = false
            error = failure
            if (result) details = result
        })
    }
    Component.onCompleted: load()

    // Paused or stopped: where the episode got to has moved.
    Connections {
        target: Player
        function onPlayingChanged() { if (!Player.playing) reload.restart() }
    }
    Timer { id: reload; interval: 1000; onTriggered: page.load() }

    readonly property var queueArgs: ({
        title: series?.title ?? "", imageUri: series?.imageUri ?? "",
        episodes: details?.episodes ?? [], startIndex: index,
    })

    /** Show notes as styled text: paragraphs, emphasis and links; images left out. */
    function notes(html) {
        if (!html.includes("<")) return html.replace(/\n/g, "<br>")
        return html.replace(/<img[^>]*>/gi, "").replace(/<(script|style)[^>]*>[\s\S]*?<\/\1>/gi, "")
    }

    function published(iso) {
        const date = new Date(iso)
        return isNaN(date) ? iso.slice(0, 10) : date.toLocaleDateString(Qt.locale(), Locale.LongFormat)
    }

    ColumnLayout {
        spacing: Kirigami.Units.largeSpacing

        LoadState {
            Layout.alignment: Qt.AlignHCenter
            loading: page.loading && !page.details
            error: page.details ? null : page.error
            onRetry: page.load()
        }
        Kirigami.PlaceholderMessage {
            Layout.fillWidth: true
            visible: !!page.details && !page.episode
            icon.name: "podcast-symbolic"
            text: KI18n.i18n("Episode not found")
        }

        GridLayout {
            Layout.fillWidth: true
            visible: !!page.episode
            columns: page.width > Kirigami.Units.gridUnit * 36 ? 2 : 1
            columnSpacing: Kirigami.Units.largeSpacing * 2
            rowSpacing: Kirigami.Units.largeSpacing

            // Episode art is often wide (a video still): shown whole.
            Image {
                Layout.preferredWidth: Kirigami.Units.gridUnit * 14
                // As tall as the art at that width (square or wide), no taller than square.
                Layout.preferredHeight: status === Image.Ready && implicitWidth > 0
                    ? Math.min(width, width * implicitHeight / implicitWidth) : width
                Layout.alignment: Qt.AlignTop
                source: page.episode?.imageUri || page.series?.imageUri || ""
                fillMode: Image.PreserveAspectFit
                horizontalAlignment: Image.AlignLeft
                verticalAlignment: Image.AlignTop
                asynchronous: true
                sourceSize.width: width * Screen.devicePixelRatio
            }
            ColumnLayout {
                Layout.fillWidth: true
                Layout.alignment: Qt.AlignTop
                spacing: Kirigami.Units.smallSpacing

                Kirigami.Heading {
                    Layout.fillWidth: true
                    text: page.episode?.title ?? ""
                    wrapMode: Text.Wrap
                }
                QQC2.Label {
                    text: `<a href="series">${page.series?.title ?? ""}</a>`
                    textFormat: Text.StyledText
                    font.pointSize: Kirigami.Theme.defaultFont.pointSize * 1.2
                    onLinkActivated: applicationWindow().open("PodcastPage", {seriesId: page.seriesId, series: page.series})
                    HoverHandler { cursorShape: parent.hoveredLink ? Qt.PointingHandCursor : Qt.ArrowCursor }
                }
                QQC2.Label {
                    opacity: 0.7
                    text: [page.episode ? page.published(page.episode.date) : "",
                           page.played ? KI18n.i18n("Played")
                           : page.started ? KI18n.i18n("%1 min left", page.episode.progress.minutesLeft) : ""]
                        .filter(s => s.length > 0).join(" · ")
                }
                // Partly heard: how far.
                QQC2.ProgressBar {
                    visible: page.started
                    Layout.preferredWidth: Kirigami.Units.gridUnit * 12
                    value: page.episode?.progress?.fraction ?? 0
                }

                Flow {
                    Layout.fillWidth: true
                    Layout.topMargin: Kirigami.Units.smallSpacing
                    spacing: Kirigami.Units.smallSpacing

                    QQC2.Button {
                        icon.name: "media-playback-start"
                        text: page.started ? KI18n.i18n("Resume") : KI18n.i18n("Play")
                        highlighted: true
                        onClicked: applicationWindow().playCall("episodesQueue", page.queueArgs)
                    }
                    QQC2.Button {
                        visible: page.started
                        icon.name: "media-seek-backward"
                        text: KI18n.i18n("Start Over")
                        onClicked: Core.call("episodesQueue", page.queueArgs, (queue) => {
                            if (queue) Player.play(queue, false, page.index)
                        })
                    }
                    QQC2.Button {
                        icon.name: page.played ? "edit-undo" : "checkmark"
                        text: page.played ? KI18n.i18n("Mark as Not Played") : KI18n.i18n("Mark as Played")
                        onClicked: Core.call("markPlayed", {eid: page.episode.eid, played: !page.played},
                                             (r, error) => { if (!error) page.load() })
                    }
                    QQC2.Button {
                        enabled: page.download?.state !== "Queued" && page.download?.state !== "Downloading"
                        icon.name: page.download?.state === "Done" ? "edit-delete-remove" : "download"
                        text: page.download?.state === "Done" ? KI18n.i18n("Remove Download")
                            : page.download?.state === "Downloading" ? KI18n.i18n("Downloading %1%", Math.round(page.download.fraction * 100))
                            : page.download?.state === "Queued" ? KI18n.i18n("Waiting to download")
                            : page.download?.state === "Failed" ? KI18n.i18n("Download again")
                            : KI18n.i18n("Download")
                        onClicked: {
                            if (page.download?.state === "Done") Core.call("removeDownloads", {etags: [page.episode.eid]})
                            else if (page.download?.state === "Failed") Core.call("retryDownload", {etag: page.episode.eid})
                            else applicationWindow().downloadCall("episodesQueue", page.queueArgs, page.index)
                        }
                    }
                }
            }
        }

        // The show notes.
        QQC2.Label {
            Layout.fillWidth: true
            Layout.maximumWidth: Kirigami.Units.gridUnit * 40
            visible: text.length > 0
            text: page.episode ? page.notes(page.episode.description) : ""
            textFormat: Text.StyledText
            wrapMode: Text.Wrap
            onLinkActivated: (link) => Qt.openUrlExternally(link)
            HoverHandler { cursorShape: parent.hoveredLink ? Qt.PointingHandCursor : Qt.ArrowCursor }
        }
    }
}
