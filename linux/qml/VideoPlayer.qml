import QtCore
import QtQuick
import QtQuick.Controls as QQC2
import QtQuick.Layouts
import QtQuick.Window
import org.kde.kirigami as Kirigami
import TakeoutFM.Core

/**
 * The video player, over the whole window: resumes where the title was left (or starts over),
 * picks the best audio track (the shared ranking), saves progress every 10 s and on pause,
 * and goes on to a series' next episode. Controls hide while it plays and the mouse rests.
 */
QQC2.Popup {
    id: player

    /** Closed: pages refresh their resume points and Continue Watching. */
    signal closedVideo()

    property string videoId: ""
    property var details: null
    /** Playback has really started, so a failed load never resets saved progress. */
    property bool hasPlayed: false
    property bool audioChosen: false
    property int previousVisibility: Window.Windowed

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
        icon.color: "white"
        display: QQC2.AbstractButton.IconOnly
        QQC2.ToolTip.text: text
        QQC2.ToolTip.visible: hovered
        QQC2.ToolTip.delay: Kirigami.Units.toolTipDelay
    }

    Settings {
        id: settings
        category: "video"
        /** The last audio language picked by hand, for the next title. */
        property string audioLanguage: ""
    }

    /** Plays [id] (a movie, series or episode), from its start when [startOver]. */
    function play(id, startOver) {
        Player.pause()
        if (!opened) {
            previousVisibility = applicationWindow().visibility
            open()
        }
        start(id, startOver)
    }

    function start(id, startOver) {
        videoId = id
        details = null
        hasPlayed = false
        audioChosen = false
        Core.call("videoDetails", {id: id}, (d, error) => {
            if (id !== videoId) return
            if (!d || d.videoUri === "") {
                applicationWindow().showPassiveNotification(
                    d ? qsTr("The server has no playable file for this title.") : qsTr("Can't load this title"))
                finish()
                return
            }
            details = d
            const resume = (ms) => Core.call("mediaAuthorization", {url: d.videoUri}, (auth) => {
                if (id === videoId) video.load(d.videoUri, auth ?? "", ms / 1000)
            })
            if (startOver) resume(0)
            else Core.call("resumePositionMs", {etag: d.etag}, (ms) => resume(ms ?? 0))
        })
    }

    /** Saves where it is. */
    function save() {
        if (!details || !hasPlayed || video.duration <= 0) return
        Core.call("reportProgress", {etag: details.etag, positionMs: Math.round(video.position * 1000),
                                     durationMs: Math.round(video.duration * 1000)})
    }

    function finish() {
        save()
        video.stop()
        videoId = ""
        if (applicationWindow().visibility === Window.FullScreen) applicationWindow().visibility = previousVisibility
        close()
        closedVideo()
    }

    function toggleFullScreen() {
        const window = applicationWindow()
        window.visibility = window.visibility === Window.FullScreen ? previousVisibility : Window.FullScreen
    }

    function time(seconds) {
        const s = Math.max(0, Math.floor(seconds))
        const two = n => String(n).padStart(2, "0")
        const h = Math.floor(s / 3600)
        return h > 0 ? `${h}:${two(Math.floor(s / 60) % 60)}:${two(s % 60)}` : `${Math.floor(s / 60)}:${two(s % 60)}`
    }

    function showControls() {
        controls.shown = true
        hide.restart()
    }

    Timer {
        id: saver
        interval: 10000
        repeat: true
        running: video.playing
        onTriggered: player.save()
    }
    Timer {
        id: hide
        interval: 3000
        onTriggered: if (video.playing && !audioMenu.visible && !subtitleMenu.visible && !speedMenu.visible) controls.shown = false
    }

    contentItem: Item {
        focus: true

        Keys.onPressed: (event) => {
            switch (event.key) {
            case Qt.Key_Space: case Qt.Key_K: video.togglePlay(); break
            case Qt.Key_Left: video.skip(-10); break
            case Qt.Key_Right: video.skip(10); break
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

            onPlayingChanged: {
                if (playing) {
                    player.hasPlayed = true
                    hide.restart()
                } else {
                    player.save()
                    controls.shown = true
                }
            }
            onTracksChanged: {
                if (player.audioChosen || audioTracks.length === 0) return
                player.audioChosen = true
                if (audioTracks.length === 1) return
                Core.call("bestAudioTrack", {
                    candidates: audioTracks.map(t => t.candidate),
                    preferredLanguage: settings.audioLanguage || Qt.locale().name,
                }, (best) => { if (best >= 0) video.setAudio(audioTracks[best].id) })
            }
            onEnded: {
                player.save()
                // An episode ends: on to the next one, from its start.
                if (player.details?.nextEpisodeId) player.start(player.details.nextEpisodeId, true)
                else player.finish()
            }
            onFailed: (message) => applicationWindow().showPassiveNotification(qsTr("Can't play this: %1").arg(message))
        }

        QQC2.BusyIndicator {
            anchors.centerIn: parent
            running: video.loading
            visible: running
        }

        MouseArea {
            anchors.fill: parent
            hoverEnabled: true
            cursorShape: controls.shown ? Qt.ArrowCursor : Qt.BlankCursor
            onPositionChanged: player.showControls()
            onClicked: { video.togglePlay(); player.showControls() }
            onDoubleClicked: player.toggleFullScreen()
        }

        Item {
            id: controls
            property bool shown: true
            anchors.fill: parent
            opacity: shown ? 1 : 0
            visible: opacity > 0
            Behavior on opacity { NumberAnimation { duration: Kirigami.Units.longDuration } }

            // Top: back and the title.
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
                        icon.name: "go-previous"
                        icon.color: "white"
                        text: qsTr("Back")
                        display: QQC2.AbstractButton.IconOnly
                        onClicked: player.finish()
                    }
                    QQC2.Label {
                        Layout.fillWidth: true
                        color: "white"
                        elide: Text.ElideRight
                        font.pointSize: Kirigami.Theme.defaultFont.pointSize * 1.3
                        // An episode's already reads "Breaking Bad · S1E3 …".
                        text: player.details?.title ?? ""
                    }
                }
            }

            // Bottom: seek bar and buttons.
            Rectangle {
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
                        QQC2.Label { text: player.time(seek.pressed ? seek.value : video.position); color: "white" }
                        QQC2.Slider {
                            id: seek
                            Layout.fillWidth: true
                            from: 0
                            to: Math.max(video.duration, 1)
                            Binding on value { value: video.position; when: !seek.pressed }
                            onPressedChanged: if (!pressed) video.seek(value)
                            onMoved: player.showControls()
                        }
                        QQC2.Label { text: player.time(video.duration); color: "white" }
                    }
                    RowLayout {
                        PlayerButton { icon.name: "media-seek-backward"; text: qsTr("Back 10 seconds"); onClicked: video.skip(-10) }
                        PlayerButton {
                            icon.name: video.playing ? "media-playback-pause" : "media-playback-start"
                            icon.width: Kirigami.Units.iconSizes.medium
                            icon.height: Kirigami.Units.iconSizes.medium
                            text: video.playing ? qsTr("Pause") : qsTr("Play")
                            onClicked: video.togglePlay()
                        }
                        PlayerButton { icon.name: "media-seek-forward"; text: qsTr("Forward 10 seconds"); onClicked: video.skip(10) }
                        PlayerButton {
                            visible: !!player.details?.nextEpisodeId
                            icon.name: "media-skip-forward"
                            text: qsTr("Next episode")
                            onClicked: { player.save(); player.start(player.details.nextEpisodeId, true) }
                        }
                        Item { Layout.fillWidth: true }
                        PlayerButton {
                            icon.name: "audio-volume-high"
                            text: qsTr("Audio")
                            enabled: video.audioTracks.length > 1
                            onClicked: audioMenu.popup()
                            QQC2.Menu {
                                id: audioMenu
                                Repeater {
                                    model: video.audioTracks
                                    QQC2.MenuItem {
                                        required property var modelData
                                        text: modelData.label
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
                            text: qsTr("Subtitles")
                            enabled: video.subtitleTracks.length > 0
                            onClicked: subtitleMenu.popup()
                            QQC2.Menu {
                                id: subtitleMenu
                                QQC2.MenuItem {
                                    text: qsTr("Off")
                                    checkable: true
                                    checked: !video.subtitleTracks.some(t => t.selected)
                                    onTriggered: video.setSubtitle(0)
                                }
                                Repeater {
                                    model: video.subtitleTracks
                                    QQC2.MenuItem {
                                        required property var modelData
                                        text: modelData.label
                                        checkable: true
                                        checked: modelData.selected
                                        onTriggered: video.setSubtitle(modelData.id)
                                    }
                                }
                            }
                        }
                        PlayerButton {
                            icon.name: "speedometer"
                            text: qsTr("Speed")
                            onClicked: speedMenu.popup()
                            QQC2.Menu {
                                id: speedMenu
                                Repeater {
                                    model: [0.5, 0.75, 1, 1.25, 1.5, 2]
                                    QQC2.MenuItem {
                                        required property real modelData
                                        text: modelData === 1 ? qsTr("Normal") : `${modelData}×`
                                        checkable: true
                                        checked: Math.abs(video.speed - modelData) < 0.01
                                        onTriggered: video.speed = modelData
                                    }
                                }
                            }
                        }
                        PlayerButton {
                            icon.name: applicationWindow().visibility === Window.FullScreen ? "view-restore" : "view-fullscreen"
                            text: qsTr("Full screen")
                            onClicked: player.toggleFullScreen()
                        }
                    }
                }
            }
        }
    }
}
