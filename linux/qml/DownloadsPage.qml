import QtQuick
import QtQuick.Controls as QQC2
import QtQuick.Layouts
import org.kde.kirigami as Kirigami
import TakeoutFM.Core

/** Everything downloaded, by the album, playlist, podcast or title it came with. */
Kirigami.ScrollablePage {
    id: page

    title: qsTr("Downloads")

    readonly property real usedBytes: Core.downloads.reduce((sum, d) => sum + (d.state === "Done" ? d.entry.bytes : 0), 0)

    /** [{id, title, downloads}], by title; each group's downloads by title. */
    readonly property var groups: {
        const byGroup = {}
        for (const d of Core.downloads) {
            if (!byGroup[d.entry.group]) byGroup[d.entry.group] = {id: d.entry.group, title: d.entry.groupTitle, downloads: []}
            byGroup[d.entry.group].downloads.push(d)
        }
        const list = Object.values(byGroup)
        for (const g of list) g.downloads.sort((a, b) => a.entry.title.localeCompare(b.entry.title))
        return list.sort((a, b) => a.title.localeCompare(b.title))
    }

    /** Music and episodes play as their group's downloaded items; videos in the player. */
    function play(download, group) {
        if (download.entry.video) {
            applicationWindow().playVideo(download.entry.group, false)
            return
        }
        const playable = group.downloads.filter(d => !d.entry.video && d.state === "Done")
        const start = playable.findIndex(d => d.entry.etag === download.entry.etag)
        if (start < 0) return
        const items = playable.map(d => ({
            title: d.entry.title, artist: d.entry.artist, album: d.entry.album, imageUri: d.entry.imageUri,
            backgroundUri: "", uri: d.entry.location, etag: d.entry.etag, podcast: d.entry.podcast,
        }))
        Player.play({title: group.title, creator: "", imageUri: items[0].imageUri, items: items,
                     startIndex: 0, startPositionMs: 0, location: ""}, false, start)
    }

    actions: [
        Kirigami.Action {
            displayComponent: QQC2.Label {
                text: qsTr("Uses %1").arg(applicationWindow().formatBytes(page.usedBytes))
                opacity: 0.7
                visible: page.usedBytes > 0
            }
        },
        Kirigami.Action {
            text: qsTr("Remove All")
            icon.name: "edit-delete-remove"
            enabled: Core.downloads.length > 0
            onTriggered: removeAll.open()
        }
    ]

    ColumnLayout {
        spacing: Kirigami.Units.largeSpacing

        Kirigami.PlaceholderMessage {
            Layout.fillWidth: true
            Layout.topMargin: Kirigami.Units.gridUnit * 4
            visible: Core.downloads.length === 0
            icon.name: "download"
            text: qsTr("No downloads")
            explanation: qsTr("Download albums, playlists, episodes and videos to play them offline.")
        }

        Repeater {
            model: page.groups
            ColumnLayout {
                id: group
                required property var modelData
                Layout.fillWidth: true
                spacing: 0

                RowLayout {
                    Layout.fillWidth: true
                    SectionHeading { text: group.modelData.title }
                    QQC2.Label {
                        readonly property real bytes: group.modelData.downloads.reduce((s, d) => s + d.entry.bytes, 0)
                        opacity: 0.7
                        text: bytes > 0 ? qsTr("%1 · %2").arg(group.modelData.downloads.length).arg(applicationWindow().formatBytes(bytes))
                                        : String(group.modelData.downloads.length)
                    }
                    Item { Layout.fillWidth: true }
                    QQC2.Button {
                        text: qsTr("Remove")
                        flat: true
                        onClicked: Core.call("removeDownloads", {etags: group.modelData.downloads.map(d => d.entry.etag)})
                    }
                }

                Repeater {
                    model: group.modelData.downloads
                    TrackRow {
                        id: row
                        required property var modelData
                        title: modelData.entry.title
                        subtitle: [modelData.entry.artist, modelData.entry.album]
                            .filter(s => s.length > 0 && s !== modelData.entry.title).join(" · ")
                        imageUri: modelData.entry.imageUri
                        etag: modelData.entry.etag
                        onClicked: page.play(modelData, group.modelData)
                        trailing: RowLayout {
                            QQC2.Label {
                                visible: row.modelData.state === "Done"
                                text: applicationWindow().formatBytes(row.modelData.entry.bytes)
                                opacity: 0.7
                            }
                            QQC2.ProgressBar {
                                visible: row.modelData.state === "Downloading"
                                Layout.preferredWidth: Kirigami.Units.gridUnit * 5
                                value: row.modelData.fraction
                            }
                            QQC2.ToolButton {
                                visible: row.modelData.state === "Failed"
                                icon.name: "view-refresh"
                                text: qsTr("Try again")
                                onClicked: Core.call("retryDownload", {etag: row.modelData.entry.etag})
                            }
                            QQC2.ToolButton {
                                icon.name: "edit-delete-remove"
                                text: qsTr("Remove download")
                                display: QQC2.AbstractButton.IconOnly
                                QQC2.ToolTip.text: text
                                QQC2.ToolTip.visible: hovered
                                onClicked: Core.call("removeDownloads", {etags: [row.modelData.entry.etag]})
                            }
                        }
                    }
                }
            }
        }
    }

    Kirigami.PromptDialog {
        id: removeAll
        title: qsTr("Remove all downloads?")
        subtitle: qsTr("This frees %1.").arg(applicationWindow().formatBytes(page.usedBytes))
        standardButtons: Kirigami.Dialog.Ok | Kirigami.Dialog.Cancel
        onAccepted: Core.call("removeAllDownloads")
    }
}
