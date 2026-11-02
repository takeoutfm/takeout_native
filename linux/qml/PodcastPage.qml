import QtQuick
import QtQuick.Controls as QQC2
import QtQuick.Layouts
import org.kde.kirigami as Kirigami
import TakeoutFM.Core

/** A podcast: its cover and description, subscribe, and episodes that resume where they were left. */
Kirigami.ScrollablePage {
    id: page

    required property string seriesId
    /** What the tile already knew, shown while the details load. */
    property var series: null
    property var details: null
    property bool loading: false
    property var error: null

    readonly property var shown: details?.series ?? series
    title: shown?.title ?? ""

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

    // Paused or stopped: an episode's progress has moved.
    Connections {
        target: Player
        function onPlayingChanged() { if (!Player.playing) reload.restart() }
    }
    Timer { id: reload; interval: 1000; onTriggered: page.load() }

    /** A feed's HTML description as plain text (rendering it would also fetch its images). */
    function plain(html) {
        const entities = {amp: "&", lt: "<", gt: ">", quot: "\"", apos: "'", nbsp: " "}
        return html.replace(/<\/p>|<br\s*\/?>/gi, "\n").replace(/<[^>]*>/g, "")
            .replace(/&(#\d+|\w+);/g, (m, e) => e[0] === "#" ? String.fromCharCode(parseInt(e.slice(1))) : (entities[e] ?? m))
            .trim()
    }

    /** "12 min left", "Played", or the date. */
    function status(episode) {
        const progress = episode.progress
        if (progress?.type === "Complete") return qsTr("Played")
        if (progress?.type === "Started") return qsTr("%1 min left").arg(progress.minutesLeft)
        return ""
    }

    actions: [
        Kirigami.Action {
            visible: !!page.details
            text: page.details?.subscribed ? qsTr("Unsubscribe") : qsTr("Subscribe")
            icon.name: page.details?.subscribed ? "list-remove" : "list-add"
            onTriggered: Core.call("setSubscribed", {seriesId: page.seriesId, subscribed: !page.details.subscribed},
                                   (r, error) => { if (!error) page.load() })
        }
    ]

    ColumnLayout {
        spacing: Kirigami.Units.largeSpacing

        RowLayout {
            Layout.fillWidth: true
            spacing: Kirigami.Units.largeSpacing * 2

            Image {
                Layout.preferredWidth: Kirigami.Units.gridUnit * 10
                Layout.preferredHeight: Kirigami.Units.gridUnit * 10
                Layout.alignment: Qt.AlignTop
                source: page.shown?.imageUri ?? ""
                fillMode: Image.PreserveAspectFit
                asynchronous: true
                sourceSize.width: width * Screen.devicePixelRatio
            }
            ColumnLayout {
                Layout.fillWidth: true
                Kirigami.Heading {
                    Layout.fillWidth: true
                    text: page.shown?.title ?? ""
                    wrapMode: Text.Wrap
                }
                QQC2.Label {
                    Layout.fillWidth: true
                    text: page.shown?.author ?? ""
                    opacity: 0.8
                }
                QQC2.Label {
                    Layout.fillWidth: true
                    text: page.plain(page.shown?.description ?? "")
                    wrapMode: Text.Wrap
                    maximumLineCount: 6
                    elide: Text.ElideRight
                    textFormat: Text.PlainText
                    opacity: 0.8
                }
            }
        }

        LoadState {
            Layout.alignment: Qt.AlignHCenter
            loading: page.loading && !page.details
            error: page.details ? null : page.error
            onRetry: page.load()
        }

        Repeater {
            model: page.details?.episodes ?? []
            TrackRow {
                required property var modelData
                required property int index
                title: modelData.title
                subtitle: [modelData.date.slice(0, 10), page.status(modelData)].filter(s => s.length > 0).join(" · ")
                readonly property var queueArgs: ({
                    title: page.shown?.title ?? "", imageUri: page.shown?.imageUri ?? "",
                    episodes: page.details.episodes, startIndex: index})
                onClicked: applicationWindow().open("PodcastEpisodePage", {
                    seriesId: page.seriesId, episodeId: modelData.id, details: page.details})
                // Episodes' progress and downloads are keyed by their GUID.
                etag: modelData.eid
                trailing: RowLayout {
                    QQC2.ToolButton {
                        visible: !download
                        icon.name: "download"
                        text: qsTr("Download")
                        display: QQC2.AbstractButton.IconOnly
                        QQC2.ToolTip.text: text
                        QQC2.ToolTip.visible: hovered
                        onClicked: applicationWindow().downloadCall("episodesQueue", queueArgs, index)
                    }
                    QQC2.ToolButton {
                        icon.name: "media-playback-start"
                        text: qsTr("Play")
                        display: QQC2.AbstractButton.IconOnly
                        QQC2.ToolTip.text: text
                        QQC2.ToolTip.visible: hovered
                        onClicked: applicationWindow().playCall("episodesQueue", queueArgs)
                    }
                }
            }
        }
    }
}
