import QtQuick
import QtQuick.Controls as QQC2
import QtQuick.Layouts
import org.kde.kirigami as Kirigami
import TakeoutFM
import org.kde.ki18n

/** Radio: live streams and the server's mixes (genres, decades, artists…), by kind. */
AppPage {
    id: page

    title: KI18n.i18n("Radio")

    property var groups: []
    property bool loading: false
    property var error: null

    readonly property var kindNames: ({
        Stream: KI18n.i18n("Streams"), Genre: KI18n.i18n("Genres"), Period: KI18n.i18n("Decades"),
        Artist: KI18n.i18n("Artists"), Similar: KI18n.i18n("Similar"), Series: KI18n.i18n("Series"), Other: KI18n.i18n("Other"),
    })

    function load() {
        loading = true
        error = null
        Core.call("radio", {}, (result, failure) => {
            loading = false
            error = failure
            groups = result ?? []
        })
    }
    Component.onCompleted: load()

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
                            width: group.width / tiles.columns
                        }
                    }
                }
            }
        }
    }
}
