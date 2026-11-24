import QtQuick
import QtQuick.Controls as QQC2
import QtQuick.Layouts
import org.kde.kirigami as Kirigami
import org.kde.kirigami.delegates as KD
import TakeoutFM
import org.kde.ki18n

/**
 * Activity (LIB-14): the server's play stats, as on the phone and Mac: the most played artists,
 * releases or tracks over a period (Recent, Last Week, This Month, Last Month), each with its
 * play count. An artist or release opens its page; a track plays from there with the rest.
 */
AppPage {
    id: page

    title: KI18n.i18n("Activity")

    /** 0 artists, 1 releases, 2 tracks. */
    property int kind: 0
    /** The shared ActivityInterval's names, in the phone's order. */
    readonly property var intervals: ["Recent", "LastWeek", "ThisMonth", "LastMonth"]
    property int interval: 0

    property var stats: null
    property bool loading: false
    property var error: null

    function load() {
        loading = true
        error = null
        const asked = interval
        Core.call("activity", {interval: intervals[asked]}, (result, failure) => {
            if (asked !== page.interval) return  // another period was picked meanwhile
            loading = false
            error = failure
            if (result) stats = result
        })
    }
    Component.onCompleted: load()
    onIntervalChanged: load()

    // Without the entries the server sends with no name (a release no longer in the library
    // came back as a blank row that opened nothing).
    readonly property var rows: !stats ? []
        : kind === 0 ? stats.artists.filter(c => c.item?.name)
        : kind === 1 ? stats.albums.filter(c => c.item?.name)
        : stats.tracks.filter(c => c.item?.title)

    function plays(count) { return KI18n.i18np("1 play", "%1 plays", count) }

    header: QQC2.Control {
        padding: Kirigami.Units.smallSpacing
        contentItem: ColumnLayout {
            spacing: Kirigami.Units.smallSpacing
            QQC2.TabBar {
                Layout.fillWidth: true
                currentIndex: page.kind
                onCurrentIndexChanged: page.kind = currentIndex
                QQC2.TabButton { text: KI18n.i18n("Artists") }
                QQC2.TabButton { text: KI18n.i18n("Releases") }
                QQC2.TabButton { text: KI18n.i18n("Tracks") }
            }
            QQC2.TabBar {
                Layout.fillWidth: true
                currentIndex: page.interval
                onCurrentIndexChanged: page.interval = currentIndex
                QQC2.TabButton { text: KI18n.i18n("Recent") }
                QQC2.TabButton { text: KI18n.i18n("Last Week") }
                QQC2.TabButton { text: KI18n.i18n("This Month") }
                QQC2.TabButton { text: KI18n.i18n("Last Month") }
            }
        }
    }

    ListView {
        model: page.rows
        // No row is current (or looks selected) until the arrow keys pick one.
        currentIndex: -1
        onCountChanged: currentIndex = -1
        keyNavigationEnabled: true

        delegate: Loader {
            id: entry
            required property var modelData
            required property int index
            width: ListView.view.width
            focus: ListView.isCurrentItem
            sourceComponent: page.kind === 2 ? trackRow : itemRow
        }

        Component {
            id: itemRow
            KD.SubtitleDelegate {
                id: row
                readonly property var item: parent.modelData.item
                readonly property bool artist: page.kind === 0
                width: parent.width
                text: item.name
                subtitle: artist ? "" : item.artist
                onClicked: artist
                    ? applicationWindow().open("ArtistPage", {artistId: item.id, artist: item})
                    : applicationWindow().open("AlbumPage", {albumId: item.id, album: item})
                contentItem: RowLayout {
                    spacing: Kirigami.Units.largeSpacing
                    Image {
                        // the server gives artists here no picture
                        visible: source != ""
                        Layout.preferredWidth: Kirigami.Units.iconSizes.large
                        Layout.preferredHeight: Kirigami.Units.iconSizes.large
                        source: row.artist ? (row.item.imageUri ?? "") : (row.item.coverUri ?? "")
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
                        text: page.plays(row.parent.modelData.count)
                        opacity: 0.7
                    }
                }
            }
        }

        Component {
            id: trackRow
            TrackRow {
                id: track
                readonly property var item: parent.modelData.item
                readonly property int count: parent.modelData.count
                width: parent.width
                title: item.title
                subtitle: [item.artist, item.album].filter(s => s).join(" · ")
                imageUri: item.coverUri ?? ""
                trackId: item.id
                etag: item.etag ?? ""
                trailing: Component {
                    QQC2.Label { text: page.plays(track.count); opacity: 0.7 }
                }
                onClicked: applicationWindow().playCall("tracksQueue", {
                    title: KI18n.i18n("Activity"),
                    tracks: page.rows.map(c => c.item),
                    startIndex: parent.index,
                })
            }
        }

        Kirigami.PlaceholderMessage {
            anchors.centerIn: parent
            width: parent.width - Kirigami.Units.gridUnit * 4
            visible: !!page.stats && page.rows.length === 0 && !page.loading
            icon.name: "office-chart-bar"
            text: KI18n.i18n("Nothing played")
            explanation: KI18n.i18n("Plays show here when Track activity is on.")
        }
        LoadState {
            anchors.centerIn: parent
            loading: page.loading && !page.stats
            error: page.stats ? null : page.error
            onRetry: page.load()
        }
    }
}
