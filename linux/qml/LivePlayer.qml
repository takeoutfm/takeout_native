import QtQuick
import QtQuick.Controls as QQC2
import QtQuick.Layouts
import QtQuick.Window
import org.kde.kirigami as Kirigami
import TakeoutFM
import org.kde.ki18n

/**
 * Live TV over the whole window: plays whatever is on a channel now from where it is now, by
 * the server's clock, then follows the schedule (the shared `tune` call decides what's on).
 * Up and Down change channel; pausing puts you behind live until the program ends or you jump
 * back. Break music plays under an up-next slate and fades out when the next program cuts it
 * off. Watch progress isn't written, so flipping past things doesn't fill Continue Watching.
 */
QQC2.Popup {
    id: player

    property string channel: ""
    /** The last `tune` answer. */
    property var tuned: null
    property bool behind: false
    property string error: ""
    property real now: Date.now()
    property int previousVisibility: Window.Windowed
    /** Bumped by each tune, so a late answer for an old one is dropped. */
    property int generation: 0

    readonly property var program: tuned?.program ?? null
    readonly property var track: tuned?.track ?? null
    readonly property var next: tuned?.next ?? null
    readonly property bool playingProgram: tuned?.kind === "program"

    parent: QQC2.Overlay.overlay
    x: 0
    y: 0
    width: parent ? parent.width : 0
    height: parent ? parent.height : 0
    z: 1000
    modal: true
    padding: 0
    closePolicy: QQC2.Popup.NoAutoClose
    background: Rectangle { color: "black" }

    /** Opens the player on [name]. */
    function play(name) {
        Player.pause()
        if (!opened) {
            previousVisibility = applicationWindow().visibility
            open()
        }
        channel = name
        tune()
    }

    function serverNow() {
        return Date.now() + (tuned?.skewMs ?? 0)
    }

    /** Asks what's on now and plays it. */
    function tune() {
        const g = ++generation
        behind = false
        error = ""
        retune.stop()
        Core.call("tune", {channel: channel}, (t, failure) => {
            if (g !== generation) return
            if (!t) {
                tuned = null
                video.stop()
                error = KI18n.i18n("Couldn't load the guide. Retrying…")
                wait(30000)
                return
            }
            tuned = t
            now = serverNow()
            showControls()
            switch (t.kind) {
            case "program":
            case "music": {
                const p = t.kind === "program" ? t.program : t.track
                video.volume = 1
                Core.call("mediaAuthorization", {url: p.videoUri}, (auth) => {
                    if (g === generation) video.load(p.videoUri, auth ?? "", t.offsetMs / 1000)
                })
                wait(t.untilMs - serverNow())
                break
            }
            case "slate":
                video.stop()
                wait(t.untilMs > 0 ? t.untilMs - serverNow() : 5 * 60 * 1000)
                break
            default:
                video.stop()
                error = KI18n.i18n("%1 isn't on right now.", channel)
                wait(30000)
            }
        })
    }

    /** Tunes again in [ms] (a second at least, five minutes at most). */
    function wait(ms) {
        retune.interval = Math.min(Math.max(ms, 1000), 5 * 60 * 1000)
        retune.restart()
    }

    function step(delta) {
        const lineup = tuned?.lineup ?? []
        if (lineup.length === 0) return
        const index = Math.max(0, lineup.indexOf(channel))
        channel = lineup[(index + delta + lineup.length) % lineup.length]
        tune()
    }

    function togglePause() {
        if (!playingProgram) return
        if (video.playing) {
            video.pause()
            behind = true
        } else {
            video.togglePlay()
        }
        showControls()
    }

    function finish() {
        ++generation
        retune.stop()
        video.stop()
        tuned = null
        if (applicationWindow().visibility === Window.FullScreen) applicationWindow().visibility = previousVisibility
        close()
    }

    function toggleFullScreen() {
        const window = applicationWindow()
        window.visibility = window.visibility === Window.FullScreen ? previousVisibility : Window.FullScreen
    }

    function showControls() {
        controls.shown = true
        hide.restart()
    }

    function time(ms) {
        return new Date(ms).toLocaleTimeString(Qt.locale(), Locale.ShortFormat)
    }

    function countdown(ms) {
        const s = Math.max(0, Math.floor(ms / 1000))
        const two = n => String(n).padStart(2, "0")
        const h = Math.floor(s / 3600)
        return h > 0 ? `${h}:${two(Math.floor(s / 60) % 60)}:${two(s % 60)}` : `${Math.floor(s / 60)}:${two(s % 60)}`
    }

    function programTitle(p) {
        return p.kind === "offair" ? KI18n.i18n("Off Air") : p.title
    }

    Timer {
        id: retune
        // Behind live, the program plays on until its file ends.
        onTriggered: if (!(player.behind && player.playingProgram)) player.tune()
    }
    Timer {
        // the clock, and break music fading out
        interval: 100
        repeat: true
        running: player.opened
        onTriggered: {
            player.now = player.serverNow()
            const t = player.tuned
            if (t?.kind === "music" && t.fadeFromMs > 0 && player.now >= t.fadeFromMs) {
                video.volume = Math.max(0, (t.untilMs - player.now) / (t.untilMs - t.fadeFromMs))
            }
        }
    }
    Timer {
        id: hide
        interval: 4000
        onTriggered: if (video.playing) controls.shown = false
    }

    contentItem: Item {
        focus: true

        Keys.onPressed: (event) => {
            switch (event.key) {
            case Qt.Key_Up: case Qt.Key_PageUp: player.step(-1); break
            case Qt.Key_Down: case Qt.Key_PageDown: player.step(1); break
            case Qt.Key_Space: case Qt.Key_K: player.togglePause(); break
            case Qt.Key_L: if (player.behind) player.tune(); break
            case Qt.Key_F: player.toggleFullScreen(); break
            case Qt.Key_Escape:
                if (applicationWindow().visibility === Window.FullScreen) player.toggleFullScreen()
                else player.finish()
                break
            default: return
            }
            player.showControls()
            event.accepted = true
        }

        VideoView {
            id: video
            anchors.fill: parent
            visible: player.playingProgram
            onEnded: {
                // Behind live: back to whatever is on now. A file shorter than its slot: the
                // slate until the slot ends (the retune timer is already set for then).
                if (player.behind) player.tune()
                else if (player.playingProgram) player.tuned = Object.assign({}, player.tuned, {kind: "slate", program: null})
            }
            onFailed: (message) => player.error = KI18n.i18n("Can't play this: %1", message)
        }

        QQC2.BusyIndicator {
            anchors.centerIn: parent
            running: (player.playingProgram && video.loading && !player.error) || (!player.tuned && !player.error)
            visible: running
        }

        // ---- The slate: a break, or a program off air ----
        Rectangle {
            anchors.centerIn: parent
            visible: !!player.tuned && player.tuned.kind !== "program" && player.tuned.kind !== "missing" && !player.error
            width: Math.min(parent.width - Kirigami.Units.gridUnit * 4, Kirigami.Units.gridUnit * 24)
            height: slate.implicitHeight + Kirigami.Units.gridUnit * 2
            radius: Kirigami.Units.cornerRadius * 2
            color: Qt.rgba(0, 0, 0, 0.8)
            Kirigami.Theme.colorSet: Kirigami.Theme.Complementary
            Kirigami.Theme.inherit: false

            ColumnLayout {
                id: slate
                anchors { left: parent.left; right: parent.right; verticalCenter: parent.verticalCenter; margins: Kirigami.Units.gridUnit }
                spacing: Kirigami.Units.smallSpacing

                RowLayout {
                    visible: !!player.track
                    Layout.alignment: Qt.AlignHCenter
                    spacing: Kirigami.Units.largeSpacing
                    Image {
                        visible: !!player.track?.imageUri
                        source: player.track?.imageUri ?? ""
                        Layout.preferredWidth: Kirigami.Units.gridUnit * 3
                        Layout.preferredHeight: Kirigami.Units.gridUnit * 3
                        fillMode: Image.PreserveAspectCrop
                    }
                    ColumnLayout {
                        QQC2.Label { text: player.track?.title ?? ""; font.bold: true; elide: Text.ElideRight; Layout.maximumWidth: Kirigami.Units.gridUnit * 16 }
                        QQC2.Label { text: player.track?.subtitle ?? ""; opacity: 0.7; elide: Text.ElideRight; Layout.maximumWidth: Kirigami.Units.gridUnit * 16 }
                    }
                }
                QQC2.Label {
                    visible: !player.track && !!player.program
                    Layout.alignment: Qt.AlignHCenter
                    text: KI18n.i18n("Off Air")
                    font.bold: true
                }
                QQC2.Label {
                    Layout.alignment: Qt.AlignHCenter
                    text: player.next ? KI18n.i18n("Up next") : KI18n.i18n("Nothing scheduled yet")
                    opacity: 0.7
                }
                Kirigami.Heading {
                    visible: !!player.next
                    Layout.fillWidth: true
                    horizontalAlignment: Text.AlignHCenter
                    wrapMode: Text.WordWrap
                    level: 2
                    text: player.next ? player.programTitle(player.next) : ""
                }
                QQC2.Label {
                    visible: !!player.next && player.next.startMs > player.now
                    Layout.alignment: Qt.AlignHCenter
                    text: player.next ? KI18n.i18n("Starts in %1", player.countdown(player.next.startMs - player.now)) : ""
                }
            }
        }

        Kirigami.PlaceholderMessage {
            anchors.centerIn: parent
            width: parent.width - Kirigami.Units.gridUnit * 4
            visible: player.error !== ""
            Kirigami.Theme.colorSet: Kirigami.Theme.Complementary
            Kirigami.Theme.inherit: false
            icon.name: "dialog-warning"
            text: player.error
        }

        MouseArea {
            anchors.fill: parent
            hoverEnabled: true
            cursorShape: controls.shown ? Qt.ArrowCursor : Qt.BlankCursor
            onPositionChanged: player.showControls()
            onClicked: player.showControls()
            onDoubleClicked: player.toggleFullScreen()
        }

        Item {
            id: controls
            Kirigami.Theme.colorSet: Kirigami.Theme.Complementary
            Kirigami.Theme.inherit: false
            property bool shown: true
            anchors.fill: parent
            opacity: shown || !video.playing ? 1 : 0
            visible: opacity > 0
            Behavior on opacity { NumberAnimation { duration: Kirigami.Units.longDuration } }

            QQC2.ToolButton {
                anchors { left: parent.left; top: parent.top; margins: Kirigami.Units.largeSpacing }
                icon.name: "go-previous"
                text: KI18n.i18n("Back")
                display: QQC2.AbstractButton.IconOnly
                onClicked: player.finish()
            }

            ColumnLayout {
                anchors { right: parent.right; verticalCenter: parent.verticalCenter; margins: Kirigami.Units.largeSpacing }
                QQC2.ToolButton {
                    icon.name: "go-up"
                    text: KI18n.i18n("Previous channel")
                    display: QQC2.AbstractButton.IconOnly
                    onClicked: player.step(-1)
                }
                QQC2.ToolButton {
                    icon.name: "go-down"
                    text: KI18n.i18n("Next channel")
                    display: QQC2.AbstractButton.IconOnly
                    onClicked: player.step(1)
                }
            }

            // The banner: channel, what's on, time left, what's next.
            Rectangle {
                anchors { left: parent.left; right: parent.right; bottom: parent.bottom }
                height: banner.implicitHeight + Kirigami.Units.largeSpacing * 3
                gradient: Gradient {
                    GradientStop { position: 0; color: "transparent" }
                    GradientStop { position: 1; color: Qt.rgba(0, 0, 0, 0.85) }
                }
                ColumnLayout {
                    id: banner
                    anchors { left: parent.left; right: parent.right; bottom: parent.bottom; margins: Kirigami.Units.largeSpacing * 2 }
                    spacing: Kirigami.Units.smallSpacing

                    QQC2.Label {
                        text: player.channel
                        color: Kirigami.Theme.highlightColor
                        font.bold: true
                    }
                    RowLayout {
                        spacing: Kirigami.Units.largeSpacing
                        QQC2.ToolButton {
                            visible: player.playingProgram
                            icon.name: video.playing ? "media-playback-pause" : "media-playback-start"
                            icon.width: Kirigami.Units.iconSizes.medium
                            icon.height: Kirigami.Units.iconSizes.medium
                            text: video.playing ? KI18n.i18n("Pause") : KI18n.i18n("Play")
                            display: QQC2.AbstractButton.IconOnly
                            onClicked: player.togglePause()
                        }
                        ColumnLayout {
                            Layout.fillWidth: true
                            spacing: 0
                            QQC2.Label {
                                visible: (player.program?.block ?? "") !== ""
                                text: player.program?.block ?? ""
                                color: Kirigami.Theme.positiveTextColor
                            }
                            Kirigami.Heading {
                                Layout.fillWidth: true
                                visible: !!player.program
                                level: 2
                                elide: Text.ElideRight
                                text: player.program ? player.programTitle(player.program) : ""
                            }
                            QQC2.Label {
                                Layout.fillWidth: true
                                visible: player.program?.kind === "episode" && player.program.subtitle !== ""
                                elide: Text.ElideRight
                                text: player.program?.subtitle ?? ""
                            }
                            QQC2.Label {
                                visible: !!player.program
                                opacity: 0.8
                                text: player.program ? `${player.time(player.program.startMs)} – ${player.time(player.program.endMs)}`
                                    + (player.tuned.left ? ` · ${player.tuned.left}` : "") : ""
                            }
                        }
                        QQC2.Button {
                            visible: player.behind
                            text: KI18n.i18n("Jump to live")
                            onClicked: player.tune()
                        }
                    }
                    QQC2.ProgressBar {
                        Layout.fillWidth: true
                        visible: !!player.program
                        from: player.program?.startMs ?? 0
                        to: player.program?.endMs ?? 1
                        value: player.now
                    }
                    QQC2.Label {
                        visible: player.behind
                        text: KI18n.i18n("Behind live")
                        opacity: 0.8
                    }
                    QQC2.Label {
                        Layout.fillWidth: true
                        visible: !!player.next
                        elide: Text.ElideRight
                        opacity: 0.8
                        text: player.next ? KI18n.i18n("Next: %1 · %2", player.time(player.next.startMs), player.programTitle(player.next)) : ""
                    }
                }
            }
        }
    }
}
