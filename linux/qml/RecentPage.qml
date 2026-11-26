import QtQuick
import QtQuick.Controls as QQC2
import QtQuick.Layouts
import org.kde.kirigami as Kirigami
import org.kde.kirigami.delegates as KD
import TakeoutFM
import org.kde.ki18n

/**
 * Recently Played on this computer: queues, newest first, resumed where they were left; then
 * Stream History (RADIO-5), the songs heard on live streams, as the phone and Mac list them.
 */
AppPage {
    id: page

    title: KI18n.i18n("Recently Played")

    /** A PlayedQueue as the MusicQueue that plays it again (as the shared toQueue). */
    function toQueue(entry) {
        return {
            title: entry.title,
            creator: entry.creator,
            imageUri: entry.imageUri,
            items: entry.items,
            startIndex: Math.max(0, Math.min(entry.index, entry.items.length - 1)),
            startPositionMs: entry.positionMs,
            location: entry.location,
        }
    }

    /** "Beck · 13 tracks", or just the count when the creator is the title. */
    function detail(entry) {
        if (entry.items[0]?.live) return KI18n.i18n("Live radio")
        const n = entry.items.length
        const tracks = KI18n.i18np("1 track", "%1 tracks", n)
        return entry.creator && entry.creator !== entry.title ? `${entry.creator} · ${tracks}` : tracks
    }

    /** When, relative to now: "5 minutes ago", "yesterday", "3 days ago", else the date. */
    function played(ms) {
        const minutes = Math.floor((Date.now() - ms) / 60000)
        if (minutes < 1) return KI18n.i18n("just now")
        if (minutes < 60) return KI18n.i18np("1 minute ago", "%1 minutes ago", minutes)
        const hours = Math.floor(minutes / 60)
        if (hours < 24) return KI18n.i18np("1 hour ago", "%1 hours ago", hours)
        const days = Math.floor(hours / 24)
        if (days === 1) return KI18n.i18n("yesterday")
        if (days < 7) return KI18n.i18np("1 day ago", "%1 days ago", days)
        return new Date(ms).toLocaleDateString(Qt.locale(), Locale.ShortFormat)
    }

    readonly property bool empty: Core.recentlyPlayed.length === 0 && Core.streamHistory.length === 0

    actions: [
        Kirigami.Action {
            text: KI18n.i18n("Clear")
            icon.name: "edit-clear-history"
            enabled: !page.empty
            onTriggered: clear.open()
        }
    ]

    ListView {
        // The queues, then the songs heard on streams, each under its heading.
        model: Core.recentlyPlayed.map(e => ({kind: "queue", entry: e}))
            .concat(Core.streamHistory.map(s => ({kind: "stream", song: s})))
        section.property: "kind"
        section.delegate: Kirigami.ListSectionHeader {
            required property string section
            width: ListView.view.width
            text: section === "queue" ? KI18n.i18n("Recently Played") : KI18n.i18n("Stream History")
        }
        // No row is current (or looks selected) until the arrow keys pick one; the list is
        // given focus when the page opens, and a new model would make the first row current.
        currentIndex: -1
        onCountChanged: currentIndex = -1
        keyNavigationEnabled: true
        delegate: KD.SubtitleDelegate {
            id: row
            required property var modelData
            readonly property bool queue: modelData.kind === "queue"
            readonly property var entry: modelData.entry
            readonly property var song: modelData.song
            width: ListView.view.width
            // Only the current row holds the list's focus (a reset leaves it on the first).
            focus: ListView.isCurrentItem
            // A song heard on a stream is only a record: nothing to replay.
            hoverEnabled: queue
            down: queue && pressed
            text: queue ? entry.title : song.song
            // song: artist · station
            subtitle: queue ? page.detail(entry) : [song.artist, song.station].filter(s => s).join(" · ")
            // A live station tunes in again, to what's on now.
            onClicked: if (queue) {
                if (entry.items[0]?.live) applicationWindow().playCall("replayQueue", {id: entry.id})
                else Player.play(page.toQueue(entry))
            }

            contentItem: RowLayout {
                spacing: Kirigami.Units.largeSpacing
                Image {
                    Layout.preferredWidth: Kirigami.Units.iconSizes.large
                    Layout.preferredHeight: Kirigami.Units.iconSizes.large
                    source: row.queue ? (row.entry.imageUri || (row.entry.items[0]?.imageUri ?? "")) : row.song.imageUri
                    fillMode: Image.PreserveAspectCrop
                    asynchronous: true
                    sourceSize.width: width * Screen.devicePixelRatio
                    sourceSize.height: height * Screen.devicePixelRatio
                }
                KD.TitleSubtitle {
                    Layout.fillWidth: true
                    title: row.text
                    subtitle: row.subtitle
                }
                QQC2.Label {
                    text: page.played(row.queue ? row.entry.playedAt : row.song.heardAt)
                    opacity: 0.7
                }
            }
        }

        Kirigami.PlaceholderMessage {
            anchors.centerIn: parent
            width: parent.width - Kirigami.Units.gridUnit * 4
            visible: page.empty
            icon.name: "document-open-recent"
            text: KI18n.i18n("Nothing played yet")
        }
    }

    Kirigami.PromptDialog {
        id: clear
        title: KI18n.i18n("Clear Recently Played?")
        subtitle: KI18n.i18n("Recently Played and Stream History on this computer are both cleared.")
        standardButtons: Kirigami.Dialog.Ok | Kirigami.Dialog.Cancel
        onAccepted: Core.call("clearHistory")
    }
}
