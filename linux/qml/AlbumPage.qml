import QtQuick
import QtQuick.Controls as QQC2
import QtQuick.Layouts
import org.kde.kirigami as Kirigami
import TakeoutFM.Core

/** An album: cover, artist and year, its tracks by disc, and similar albums. */
Kirigami.ScrollablePage {
    id: page

    required property string albumId
    /** What the tile already knew, shown while the details load. */
    property var album: null
    property var details: null
    property bool loading: false
    property var error: null

    readonly property var shown: details?.album ?? album
    title: shown?.name ?? ""

    function load() {
        loading = true
        error = null
        Core.call("albumDetails", {albumId: albumId}, (result, failure) => {
            loading = false
            error = failure
            if (result) details = result
        })
    }
    Component.onCompleted: load()

    ColumnLayout {
        spacing: Kirigami.Units.largeSpacing

        RowLayout {
            Layout.fillWidth: true
            spacing: Kirigami.Units.largeSpacing * 2

            Image {
                Layout.preferredWidth: Kirigami.Units.gridUnit * 12
                Layout.preferredHeight: Kirigami.Units.gridUnit * 12
                source: page.shown?.coverUri ?? ""
                fillMode: Image.PreserveAspectFit
                asynchronous: true
                sourceSize.width: width * Screen.devicePixelRatio
            }
            ColumnLayout {
                Layout.fillWidth: true
                Layout.alignment: Qt.AlignBottom
                Kirigami.Heading {
                    Layout.fillWidth: true
                    text: page.shown?.name ?? ""
                    wrapMode: Text.Wrap
                }
                QQC2.Label {
                    Layout.fillWidth: true
                    text: page.details?.artistId ? "<a href=\"artist\">%1</a>".arg(page.shown.artist) : (page.shown?.artist ?? "")
                    textFormat: Text.StyledText
                    font.pointSize: Kirigami.Theme.defaultFont.pointSize * 1.2
                    onLinkActivated: applicationWindow().open("ArtistPage", {artistId: page.details.artistId})
                    HoverHandler { cursorShape: parent.hoveredLink ? Qt.PointingHandCursor : Qt.ArrowCursor }
                }
                QQC2.Label {
                    text: [page.shown?.year ?? "", page.shown?.type ?? ""].filter(s => s.length > 0).join(" · ")
                    opacity: 0.7
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
            model: page.details?.tracks ?? []
            ColumnLayout {
                required property var modelData
                required property int index
                readonly property bool newDisc: page.details.discCount > 1
                    && (index === 0 || page.details.tracks[index - 1].discNum !== modelData.discNum)
                Layout.fillWidth: true
                spacing: 0

                SectionHeading {
                    visible: parent.newDisc
                    level: 4
                    text: qsTr("Disc %1").arg(modelData.discNum)
                }
                TrackRow {
                    number: String(modelData.trackNum)
                    title: modelData.title
                    subtitle: modelData.artist !== page.shown?.artist ? modelData.artist : ""
                    favorite: Core.favoriteTracks.includes(modelData.etag)
                }
            }
        }

        AlbumFlow { title: qsTr("Similar albums"); albums: page.details?.similar ?? [] }
    }
}
