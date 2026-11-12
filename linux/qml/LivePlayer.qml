import QtCore
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
 * Channels change from the guide; pausing puts you behind live until the program ends or you jump
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
    /** The shared labels ({title, detail}) of video.audioTracks, in the same order. */
    property var audioLabels: []
    /** The tracks audioLabels are for, so a stale answer (or a selection) is told apart. */
    property string tracksFor: ""
    /** The subtitle language last chosen here ("" off), for the programs after; null: none yet. */
    property var subtitleLanguage: null
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

    component PlayerButton: QQC2.ToolButton {
        display: QQC2.AbstractButton.IconOnly
        QQC2.ToolTip.text: text
        QQC2.ToolTip.visible: hovered
        QQC2.ToolTip.delay: Kirigami.Units.toolTipDelay
    }

    Settings {
        id: settings
        // the video player's: an audio language picked in either is used by both
        category: "video"
        property string audioLanguage: ""
    }

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
        onTriggered: if (video.playing && !audioMenu.visible && !subtitleMenu.visible) controls.shown = false
    }

    contentItem: Item {
        focus: true

        Keys.onPressed: (event) => {
            switch (event.key) {
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
            onTracksChanged: {
                // Selecting a track changes the list too: only new tracks (a new program) count.
                const ids = audioTracks.map(t => t.id).join(",") + "/" + subtitleTracks.map(t => t.id).join(",")
                if (audioTracks.length === 0 || ids === player.tracksFor) return
                player.tracksFor = ids
                const tracks = audioTracks
                // subtitles as last chosen here, by language ("" off)
                if (player.subtitleLanguage !== null) {
                    const sub = subtitleTracks.find(t => t.language === player.subtitleLanguage)
                    video.setSubtitle(player.subtitleLanguage === "" || !sub ? 0 : sub.id)
                }
                // audio: the shared ranking, in the language chosen last (here or in the video player)
                Core.call("audioTracks", {
                    tracks: tracks.map(t => t.info),
                    preferredLanguage: settings.audioLanguage || Qt.locale().name,
                }, (result) => {
                    if (!result || ids !== player.tracksFor) return
                    player.audioLabels = result.labels
                    if (tracks.length > 1 && result.best >= 0) video.setAudio(tracks[result.best].id)
                })
            }
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
            // as the video player: a click pauses (live, then you're behind)
            onClicked: { player.togglePause(); player.showControls() }
            onDoubleClicked: player.toggleFullScreen()
        }

        // The video player's controls, with live's differences: no seeking or speed; the
        // program's progress in the seek bar's place; Jump to live after a pause.
        Item {
            id: controls
            Kirigami.Theme.colorSet: Kirigami.Theme.Complementary
            Kirigami.Theme.inherit: false
            property bool shown: true
            anchors.fill: parent
            opacity: shown || !video.playing ? 1 : 0
            visible: opacity > 0
            Behavior on opacity { NumberAnimation { duration: Kirigami.Units.longDuration } }

            // Top: back and the title, with the channel and what's next.
            Rectangle {
                anchors { left: parent.left; right: parent.right; top: parent.top }
                height: top.implicitHeight + Kirigami.Units.largeSpacing * 2
                gradient: Gradient {
                    GradientStop { position: 0; color: Qt.rgba(0, 0, 0, 0.7) }
                    GradientStop { position: 1; color: "transparent" }
                }
                RowLayout {
                    id: top
                    anchors { left: parent.left; right: parent.right; top: parent.top; margins: Kirigami.Units.largeSpacing }
                    QQC2.ToolButton {
                        Layout.alignment: Qt.AlignTop
                        icon.name: "go-previous"
                        text: KI18n.i18n("Back")
                        display: QQC2.AbstractButton.IconOnly
                        onClicked: player.finish()
                    }
                    ColumnLayout {
                        Layout.fillWidth: true
                        spacing: 0
                        RowLayout {
                            Layout.fillWidth: true
                            QQC2.Label {
                                Layout.fillWidth: true
                                elide: Text.ElideRight
                                font.pointSize: Kirigami.Theme.defaultFont.pointSize * 1.3
                                text: player.program ? player.programTitle(player.program)
                                    : player.next ? KI18n.i18n("Up next: %1", player.programTitle(player.next)) : player.channel
                            }
                            // live, unless paused behind it
                            QQC2.Label {
                                visible: player.playingProgram && !player.behind && video.playing
                                text: KI18n.i18n("Live")
                                color: "white"
                                font.bold: true
                                leftPadding: Kirigami.Units.smallSpacing * 2
                                rightPadding: Kirigami.Units.smallSpacing * 2
                                background: Rectangle { color: "#cc0000"; radius: height / 2 }
                            }
                        }
                        QQC2.Label {
                            Layout.fillWidth: true
                            elide: Text.ElideRight
                            opacity: 0.8
                            text: [
                                player.channel,
                                player.program?.block ?? "",
                                player.program?.kind === "episode" ? player.program.subtitle : "",
                                player.behind ? KI18n.i18n("Behind live") : "",
                            ].filter(s => s).join(" · ")
                        }
                        QQC2.Label {
                            Layout.fillWidth: true
                            visible: !!player.next && !!player.program
                            elide: Text.ElideRight
                            opacity: 0.7
                            text: player.next ? KI18n.i18n("Next: %1 · %2", player.time(player.next.startMs), player.programTitle(player.next)) : ""
                        }
                    }
                }
            }

            // Bottom: the program's progress, then the buttons.
            Rectangle {
                visible: player.playingProgram
                anchors { left: parent.left; right: parent.right; bottom: parent.bottom }
                height: bottom.implicitHeight + Kirigami.Units.largeSpacing * 3
                gradient: Gradient {
                    GradientStop { position: 0; color: "transparent" }
                    GradientStop { position: 1; color: Qt.rgba(0, 0, 0, 0.8) }
                }
                ColumnLayout {
                    id: bottom
                    anchors { left: parent.left; right: parent.right; bottom: parent.bottom; margins: Kirigami.Units.largeSpacing }

                    RowLayout {
                        QQC2.Label { text: player.program ? player.time(player.program.startMs) : "" }
                        QQC2.ProgressBar {
                            Layout.fillWidth: true
                            from: player.program?.startMs ?? 0
                            to: player.program?.endMs ?? 1
                            value: player.now
                        }
                        QQC2.Label {
                            text: player.program ? player.time(player.program.endMs)
                                + (player.tuned?.left ? ` · ${player.tuned.left}` : "") : ""
                        }
                    }
                    RowLayout {
                        PlayerButton {
                            icon.name: video.playing ? "media-playback-pause" : "media-playback-start"
                            icon.width: Kirigami.Units.iconSizes.medium
                            icon.height: Kirigami.Units.iconSizes.medium
                            text: video.playing ? KI18n.i18n("Pause") : KI18n.i18n("Play")
                            onClicked: player.togglePause()
                        }
                        QQC2.Button {
                            visible: player.behind
                            text: KI18n.i18n("Jump to live")
                            onClicked: player.tune()
                        }
                        Item { Layout.fillWidth: true }
                        PlayerButton {
                            icon.name: "audio-volume-high"
                            text: KI18n.i18n("Audio")
                            enabled: video.audioTracks.length > 1
                            onClicked: audioMenu.popup()
                            QQC2.Menu {
                                id: audioMenu
                                Repeater {
                                    model: video.audioTracks
                                    QQC2.MenuItem {
                                        required property var modelData
                                        required property int index
                                        // "Dolby Digital · Surround 5.1 — English"
                                        readonly property var shared: player.audioLabels[index]
                                        text: shared ? [shared.title, shared.detail].filter(s => s).join(" — ") : modelData.label
                                        checkable: true
                                        checked: modelData.selected
                                        onTriggered: {
                                            video.setAudio(modelData.id)
                                            if (modelData.language) settings.audioLanguage = modelData.language
                                        }
                                    }
                                }
                            }
                        }
                        PlayerButton {
                            icon.name: "media-view-subtitles-symbolic"
                            text: KI18n.i18n("Subtitles")
                            enabled: video.subtitleTracks.length > 0
                            onClicked: subtitleMenu.popup()
                            QQC2.Menu {
                                id: subtitleMenu
                                QQC2.MenuItem {
                                    text: KI18n.i18n("Off")
                                    checkable: true
                                    checked: !video.subtitleTracks.some(t => t.selected)
                                    onTriggered: {
                                        video.setSubtitle(0)
                                        player.subtitleLanguage = ""
                                    }
                                }
                                Repeater {
                                    model: video.subtitleTracks
                                    QQC2.MenuItem {
                                        required property var modelData
                                        text: modelData.label
                                        checkable: true
                                        checked: modelData.selected
                                        onTriggered: {
                                            video.setSubtitle(modelData.id)
                                            player.subtitleLanguage = modelData.language ?? ""
                                        }
                                    }
                                }
                            }
                        }
                        PlayerButton {
                            icon.name: applicationWindow().visibility === Window.FullScreen ? "view-restore" : "view-fullscreen"
                            text: KI18n.i18n("Full screen")
                            onClicked: player.toggleFullScreen()
                        }
                    }
                }
            }
        }
    }
}
