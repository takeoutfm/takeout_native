import QtQuick
import QtQuick.Controls as QQC2
import QtQuick.Effects
import QtQuick.Layouts
import org.kde.kirigami as Kirigami
import org.kde.kirigami.delegates as KD
import TakeoutFM
import org.kde.ki18n

/** What's playing: cover, title, seek bar and controls, beside (or over) the queue. */
Kirigami.Page {
    id: page

    objectName: "nowPlaying"
    title: KI18n.i18n("Now Playing")

    readonly property var item: Player.current
    readonly property string etag: item.etag ?? ""
    /** Tracks can be favorites; streams and podcast episodes can't. */
    readonly property bool isTrack: etag.length > 0 && !(item.podcast ?? false)
    readonly property bool favorite: Core.favoriteTracks.includes(etag)
    readonly property bool wide: width > Kirigami.Units.gridUnit * 40

    // The artist's background photo behind it all, as the Mac's and the phone's: faded and
    // only lightly blurred, so it keeps its detail and the text over it stays readable.
    background: Rectangle {
        color: Kirigami.Theme.backgroundColor

        Image {
            id: backdrop
            anchors.fill: parent
            source: page.item.backgroundUri ?? ""
            fillMode: Image.PreserveAspectCrop
            asynchronous: true
            visible: false
        }
        MultiEffect {
            anchors.fill: parent
            source: backdrop
            visible: backdrop.status === Image.Ready
            opacity: 0.35
            blurEnabled: true
            blur: 0.15
            blurMax: 32
            Behavior on opacity { NumberAnimation { duration: Kirigami.Units.longDuration } }
        }
    }

    function time(seconds) {
        const s = Math.max(0, Math.floor(seconds))
        const m = Math.floor(s / 60)
        const h = Math.floor(m / 60)
        const two = n => String(n).padStart(2, "0")
        return h > 0 ? `${h}:${two(m % 60)}:${two(s % 60)}` : `${m}:${two(s % 60)}`
    }

    actions: [
        Kirigami.Action {
            visible: page.isTrack
            text: page.favorite ? KI18n.i18n("Unfavorite") : KI18n.i18n("Favorite")
            icon.name: page.favorite ? "emblem-favorite" : "emblem-favorite-symbolic"
            onTriggered: Core.call("setTrackFavorite", {etag: page.etag, favorite: !page.favorite})
        },
        Kirigami.Action {
            text: [KI18n.i18n("Repeat: off"), KI18n.i18n("Repeat: all"), KI18n.i18n("Repeat: one")][Player.repeatMode]
            icon.name: Player.repeatMode === Player.RepeatOne ? "media-repeat-single" : "media-repeat-all"
            checkable: true
            checked: Player.repeatMode !== Player.RepeatOff
            displayHint: Kirigami.DisplayHint.IconOnly
            onTriggered: Player.cycleRepeat()
        },
        Kirigami.Action {
            text: KI18n.i18n("Clear queue")
            icon.name: "edit-clear-all"
            displayHint: Kirigami.DisplayHint.AlwaysHide
            onTriggered: {
                Player.stop()
                applicationWindow().pageStack.pop()
            }
        }
    ]

    GridLayout {
        anchors.fill: parent
        columns: page.wide ? 2 : 1
        columnSpacing: Kirigami.Units.gridUnit
        rowSpacing: Kirigami.Units.largeSpacing

        ColumnLayout {
            id: player
            // Wide: 60% of the page, the queue the rest.
            readonly property real columnWidth: page.wide ? page.width * 0.6 - Kirigami.Units.gridUnit * 2 : page.width - Kirigami.Units.gridUnit * 2
            Layout.preferredWidth: columnWidth
            Layout.maximumWidth: columnWidth
            Layout.fillHeight: page.wide
            spacing: Kirigami.Units.largeSpacing

            Image {
                Layout.alignment: Qt.AlignHCenter
                readonly property real side: page.wide
                    ? Math.min(player.columnWidth, page.height - Kirigami.Units.gridUnit * 14)
                    : Math.min(player.columnWidth, page.height / 2.5)
                Layout.preferredWidth: side
                Layout.preferredHeight: side
                source: page.item.imageUri ?? ""
                fillMode: Image.PreserveAspectFit
                asynchronous: true
                sourceSize.width: side * Screen.devicePixelRatio
                sourceSize.height: side * Screen.devicePixelRatio
            }
            Kirigami.Heading {
                Layout.fillWidth: true
                text: Player.displayTitle
                horizontalAlignment: Text.AlignHCenter
                wrapMode: Text.Wrap
                maximumLineCount: 2
                elide: Text.ElideRight
            }
            QQC2.Label {
                Layout.fillWidth: true
                text: Player.displaySubtitle
                horizontalAlignment: Text.AlignHCenter
                elide: Text.ElideRight
                opacity: 0.8
            }
            QQC2.Label {
                Layout.fillWidth: true
                // A stream's song shows the station above; a track its album.
                visible: Player.streamTitle.length === 0 && text.length > 0
                text: page.item.album ?? ""
                horizontalAlignment: Text.AlignHCenter
                elide: Text.ElideRight
                opacity: 0.6
            }

            RowLayout {
                Layout.fillWidth: true
                visible: !Player.live
                QQC2.Label { text: page.time(seek.pressed ? seek.value : Player.position); opacity: 0.7 }
                QQC2.Slider {
                    id: seek
                    Layout.fillWidth: true
                    from: 0
                    to: Math.max(Player.duration, 1)
                    enabled: Player.duration > 0
                    // Follows playback, except while it's being dragged.
                    Binding on value { value: Player.position; when: !seek.pressed }
                    onPressedChanged: if (!pressed) Player.seek(value)
                }
                QQC2.Label { text: page.time(Player.duration); opacity: 0.7 }
            }
            QQC2.Label {
                Layout.alignment: Qt.AlignHCenter
                visible: Player.live
                text: KI18n.i18n("Live")
                color: Kirigami.Theme.negativeTextColor
                font.weight: Font.DemiBold
            }

            RowLayout {
                Layout.alignment: Qt.AlignHCenter
                spacing: Kirigami.Units.largeSpacing
                QQC2.ToolButton {
                    icon.name: "media-skip-backward"
                    icon.width: Kirigami.Units.iconSizes.medium
                    icon.height: Kirigami.Units.iconSizes.medium
                    display: QQC2.AbstractButton.IconOnly
                    text: KI18n.i18n("Previous")
                    onClicked: Player.previous()
                }
                QQC2.RoundButton {
                    icon.name: Player.playing ? "media-playback-pause" : "media-playback-start"
                    icon.width: Kirigami.Units.iconSizes.large
                    icon.height: Kirigami.Units.iconSizes.large
                    padding: Kirigami.Units.largeSpacing
                    display: QQC2.AbstractButton.IconOnly
                    text: Player.playing ? KI18n.i18n("Pause") : KI18n.i18n("Play")
                    onClicked: Player.togglePlay()
                }
                QQC2.ToolButton {
                    icon.name: "media-skip-forward"
                    icon.width: Kirigami.Units.iconSizes.medium
                    icon.height: Kirigami.Units.iconSizes.medium
                    display: QQC2.AbstractButton.IconOnly
                    text: KI18n.i18n("Next")
                    enabled: Player.index + 1 < Player.items.length
                    onClicked: Player.next()
                }
            }
            Item { Layout.fillHeight: true; visible: page.wide }
        }

        // The queue.
        ColumnLayout {
            Layout.fillWidth: true
            Layout.fillHeight: true
            spacing: 0

            Kirigami.Heading {
                Layout.fillWidth: true
                level: 4
                text: Player.queueTitle
                elide: Text.ElideRight
            }
            QQC2.ScrollView {
                Layout.fillWidth: true
                Layout.fillHeight: true
                ListView {
                    id: queue
                    clip: true
                    model: Player.items
                    currentIndex: Player.index
                    Component.onCompleted: positionViewAtIndex(Player.index, ListView.Center)
                    delegate: KD.SubtitleDelegate {
                        required property var modelData
                        required property int index
                        width: ListView.view.width
                        text: modelData.title
                        subtitle: modelData.artist
                        highlighted: index === Player.index
                        icon.name: index === Player.index ? (Player.playing ? "media-playback-playing" : "media-playback-paused") : ""
                        onClicked: Player.skipTo(index)
                    }
                }
            }
        }
    }
}
