import QtQuick
import QtQuick.Controls as QQC2
import QtQuick.Layouts
import QtQuick.Window
import org.kde.kirigami as Kirigami
import TakeoutFM
import org.kde.ki18n

/** Radio: live stations with what's on each (RADIO-1), then internet radio streams. */
AppPage {
    id: page

    title: KI18n.i18n("Radio")

    property var groups: []
    property bool loading: false
    property var error: null
    /** What's on each live station ({stations: {id: OnNow}, refreshAtMs}), or null. */
    property var onNow: null
    property bool fetchingNow: false

    readonly property var kindNames: ({Live: KI18n.i18n("Live"), Stream: KI18n.i18n("Streams")})

    function load() {
        loading = true
        error = null
        Core.call("radio", {}, (result, failure) => {
            loading = false
            error = failure
            groups = result ?? []
        })
        loadNow()
    }
    Component.onCompleted: load()

    function loadNow() {
        if (fetchingNow) return
        fetchingNow = true
        Core.call("radioNow", {}, result => {
            fetchingNow = false
            onNow = result ?? null
            refresh.restart()
        })
    }

    // What's on changes when a track ends: fetched again then (a minute on after a failure),
    // only while the page can be seen; repeating, and checking the clock, since a QML Timer
    // can fire a little early. Caught up when it shows again and when the computer wakes.
    Timer {
        id: refresh
        repeat: true
        running: page.visible && applicationWindow().visibility !== Window.Minimized
        interval: Math.min(10 * 60000, Math.max(2000, (page.onNow?.refreshAtMs ?? 0) > 0 ? page.onNow.refreshAtMs - Date.now() + 500 : 60000))
        onRunningChanged: if (running && (page.onNow?.refreshAtMs ?? 0) <= Date.now()) page.loadNow()
        onTriggered: if ((page.onNow?.refreshAtMs ?? 0) <= Date.now()) page.loadNow()
    }
    Connections {
        target: Core
        function onWoke() { page.loadNow() }
    }

    ColumnLayout {
        spacing: Kirigami.Units.largeSpacing

        LoadState {
            Layout.alignment: Qt.AlignHCenter
            loading: page.loading && page.groups.length === 0
            error: page.error
            onRetry: page.load()
        }

        Repeater {
            model: page.groups
            ColumnLayout {
                id: group
                required property var modelData
                Layout.fillWidth: true

                SectionHeading { text: page.kindNames[group.modelData.kind] ?? group.modelData.kind }
                Flow {
                    id: tiles
                    Layout.fillWidth: true
                    readonly property int columns: Math.max(1, Math.floor(group.width / (Kirigami.Units.gridUnit * 8)))
                    Repeater {
                        model: group.modelData.stations
                        StationTile {
                            required property var modelData
                            station: modelData
                            onNow: page.onNow?.stations?.[modelData.id] ?? null
                            width: group.width / tiles.columns
                        }
                    }
                }
            }
        }
    }
}
