import QtQuick
import QtQuick.Controls as QQC2
import QtQuick.Layouts
import org.kde.kirigami as Kirigami
import org.kde.kirigami.delegates as KD
import TakeoutFM

/** Recently Played on this computer: queues, newest first, resumed where they were left. */
AppPage {
    id: page

    title: qsTr("Recently Played")

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
        const n = entry.items.length
        const tracks = n === 1 ? qsTr("1 track") : qsTr("%1 tracks").arg(n)
        return entry.creator && entry.creator !== entry.title ? `${entry.creator} · ${tracks}` : tracks
    }

    /** When, relative to now: "5 minutes ago", "yesterday", "3 days ago", else the date. */
    function played(ms) {
        const minutes = Math.floor((Date.now() - ms) / 60000)
        if (minutes < 1) return qsTr("just now")
        if (minutes < 60) return minutes === 1 ? qsTr("1 minute ago") : qsTr("%1 minutes ago").arg(minutes)
        const hours = Math.floor(minutes / 60)
        if (hours < 24) return hours === 1 ? qsTr("1 hour ago") : qsTr("%1 hours ago").arg(hours)
        const days = Math.floor(hours / 24)
        if (days === 1) return qsTr("yesterday")
        if (days < 7) return qsTr("%1 days ago").arg(days)
        return new Date(ms).toLocaleDateString(Qt.locale(), Locale.ShortFormat)
    }

    actions: [
        Kirigami.Action {
            text: qsTr("Clear")
            icon.name: "edit-clear-history"
            enabled: Core.recentlyPlayed.length > 0
            onTriggered: clear.open()
        }
    ]

    ListView {
        model: Core.recentlyPlayed
        // No row is current (or looks selected) until the arrow keys pick one; the list is
        // given focus when the page opens, and a new model would make the first row current.
        currentIndex: -1
        onCountChanged: currentIndex = -1
        keyNavigationEnabled: true
        delegate: KD.SubtitleDelegate {
            id: row
            required property var modelData
            width: ListView.view.width
            // Only the current row holds the list's focus (a reset leaves it on the first).
            focus: ListView.isCurrentItem
            text: modelData.title
            subtitle: page.detail(modelData)
            onClicked: Player.play(page.toQueue(modelData))

            contentItem: RowLayout {
                spacing: Kirigami.Units.largeSpacing
                Image {
                    Layout.preferredWidth: Kirigami.Units.iconSizes.large
                    Layout.preferredHeight: Kirigami.Units.iconSizes.large
                    source: row.modelData.imageUri || (row.modelData.items[0]?.imageUri ?? "")
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
                    text: page.played(row.modelData.playedAt)
                    opacity: 0.7
                }
            }
        }

        Kirigami.PlaceholderMessage {
            anchors.centerIn: parent
            width: parent.width - Kirigami.Units.gridUnit * 4
            visible: Core.recentlyPlayed.length === 0
            icon.name: "document-open-recent"
            text: qsTr("Nothing played yet")
        }
    }

    Kirigami.PromptDialog {
        id: clear
        title: qsTr("Clear Recently Played?")
        standardButtons: Kirigami.Dialog.Ok | Kirigami.Dialog.Cancel
        onAccepted: Core.call("clearRecentlyPlayed")
    }
}
