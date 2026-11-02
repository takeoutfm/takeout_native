import QtQuick
import QtQuick.Controls as QQC2
import QtQuick.Layouts
import org.kde.kirigami as Kirigami
import TakeoutFM
import org.kde.ki18n

/**
 * The bar along the bottom while something is queued, as the Mac's: cover and title (opening
 * Now Playing), ♥, previous / play / next with a seek bar, or LIVE for a stream, and the queue.
 */
QQC2.ToolBar {
    id: bar

    signal open()

    readonly property var item: Player.current
    // Podcast episodes' etag is their GUID, not a track's.
    readonly property bool isTrack: (item.etag ?? "") !== "" && !(item.podcast ?? false)
    readonly property bool favorite: Core.favoriteTracks.includes(item.etag ?? "")

    position: QQC2.ToolBar.Footer
    // Hidden, it takes no room.
    height: visible ? implicitHeight : 0
    implicitHeight: content.implicitHeight + Kirigami.Units.smallSpacing * 2

    function time(seconds) {
        const s = Math.max(0, Math.floor(seconds))
        const two = n => String(n).padStart(2, "0")
        const h = Math.floor(s / 3600)
        return h > 0 ? `${h}:${two(Math.floor(s / 60) % 60)}:${two(s % 60)}` : `${Math.floor(s / 60)}:${two(s % 60)}`
    }

    component BarButton: QQC2.ToolButton {
        display: QQC2.AbstractButton.IconOnly
        QQC2.ToolTip.text: text
        QQC2.ToolTip.visible: hovered
        QQC2.ToolTip.delay: Kirigami.Units.toolTipDelay
    }

    RowLayout {
        id: content
        anchors.fill: parent
        anchors.leftMargin: Kirigami.Units.largeSpacing
        anchors.rightMargin: Kirigami.Units.largeSpacing
        spacing: Kirigami.Units.largeSpacing

        // What's playing; opens Now Playing.
        RowLayout {
            Layout.preferredWidth: Kirigami.Units.gridUnit * 16
            Layout.maximumWidth: Kirigami.Units.gridUnit * 16
            spacing: Kirigami.Units.largeSpacing

            TapHandler { onTapped: bar.open() }
            HoverHandler { cursorShape: Qt.PointingHandCursor }

            Image {
                Layout.preferredWidth: Kirigami.Units.iconSizes.large
                Layout.preferredHeight: Kirigami.Units.iconSizes.large
                source: bar.item.imageUri ?? ""
                fillMode: Image.PreserveAspectCrop
                asynchronous: true
                sourceSize.width: width * Screen.devicePixelRatio
                sourceSize.height: height * Screen.devicePixelRatio
            }
            ColumnLayout {
                Layout.fillWidth: true
                spacing: 0
                QQC2.Label {
                    Layout.fillWidth: true
                    text: Player.displayTitle
                    elide: Text.ElideRight
                    font.weight: Font.DemiBold
                }
                QQC2.Label {
                    Layout.fillWidth: true
                    text: Player.displaySubtitle
                    elide: Text.ElideRight
                    opacity: 0.7
                }
            }
        }

        BarButton {
            visible: bar.isTrack
            icon.name: bar.favorite ? "emblem-favorite" : "emblem-favorite-symbolic"
            text: bar.favorite ? KI18n.i18n("Unfavorite") : KI18n.i18n("Favorite")
            onClicked: Core.call("setTrackFavorite", {etag: bar.item.etag, favorite: !bar.favorite})
        }

        // A live stream: play / stop and LIVE.
        BarButton {
            visible: Player.live
            icon.name: Player.playing ? "media-playback-stop" : "media-playback-start"
            icon.width: Kirigami.Units.iconSizes.medium
            icon.height: Kirigami.Units.iconSizes.medium
            text: Player.playing ? KI18n.i18n("Stop") : KI18n.i18n("Play")
            onClicked: Player.togglePlay()
        }
        QQC2.Label {
            visible: Player.live
            text: KI18n.i18n("LIVE")
            color: Kirigami.Theme.negativeTextColor
            font.weight: Font.Bold
            font.pointSize: Kirigami.Theme.smallFont.pointSize
            leftPadding: Kirigami.Units.smallSpacing
            rightPadding: Kirigami.Units.smallSpacing
            background: Rectangle { color: Kirigami.Theme.negativeBackgroundColor; radius: height / 2 }
        }
        Item { visible: Player.live; Layout.fillWidth: true }

        // Anything else: previous / play / next and where it is.
        BarButton {
            visible: !Player.live
            icon.name: "media-skip-backward"
            text: KI18n.i18n("Previous")
            onClicked: Player.previous()
        }
        BarButton {
            visible: !Player.live
            icon.name: Player.playing ? "media-playback-pause" : "media-playback-start"
            icon.width: Kirigami.Units.iconSizes.medium
            icon.height: Kirigami.Units.iconSizes.medium
            text: Player.playing ? KI18n.i18n("Pause") : KI18n.i18n("Play")
            onClicked: Player.togglePlay()
        }
        BarButton {
            visible: !Player.live
            icon.name: "media-skip-forward"
            text: KI18n.i18n("Next")
            enabled: Player.index + 1 < Player.items.length
            onClicked: Player.next()
        }
        QQC2.Slider {
            id: seek
            visible: !Player.live
            Layout.fillWidth: true
            from: 0
            to: Math.max(Player.duration, 1)
            enabled: Player.duration > 0
            Binding on value { value: Player.position; when: !seek.pressed }
            onPressedChanged: if (!pressed) Player.seek(value)
        }
        QQC2.Label {
            visible: !Player.live
            text: bar.time(seek.pressed ? seek.value : Player.position) + " / " + bar.time(Player.duration)
            font.features: {"tnum": 1}
            opacity: 0.7
        }

        BarButton {
            icon.name: "view-media-playlist"
            text: KI18n.i18n("Queue")
            onClicked: bar.open()
        }
    }
}
