import QtQuick
import QtQuick.Controls as QQC2
import QtQuick.Layouts
import org.kde.kirigami as Kirigami
import TakeoutFM.Core

/** Someone in the library: photo, life and biography, then their films and shows by role. */
Kirigami.ScrollablePage {
    id: page

    required property string personId
    property string name: ""
    property var person: null
    property bool loading: false
    property var error: null

    title: person?.name ?? name

    readonly property var kindNames: ({
        MoviesActing: qsTr("Movies"),
        MoviesDirecting: qsTr("Directed"),
        MoviesWriting: qsTr("Written"),
        ShowsActing: qsTr("TV"),
        ShowsDirecting: qsTr("TV, directed"),
        ShowsWriting: qsTr("TV, written"),
    })

    function load() {
        loading = true
        error = null
        Core.call("person", {personId: personId}, (result, failure) => {
            loading = false
            error = failure
            if (result) person = result
        })
    }
    Component.onCompleted: load()

    ColumnLayout {
        spacing: Kirigami.Units.largeSpacing

        LoadState {
            Layout.alignment: Qt.AlignHCenter
            loading: page.loading && !page.person
            error: page.person ? null : page.error
            onRetry: page.load()
        }

        RowLayout {
            Layout.fillWidth: true
            visible: !!page.person
            spacing: Kirigami.Units.largeSpacing * 2

            Image {
                Layout.preferredWidth: Kirigami.Units.gridUnit * 9
                Layout.preferredHeight: Kirigami.Units.gridUnit * 13.5
                Layout.alignment: Qt.AlignTop
                source: page.person?.photoUri ?? ""
                fillMode: Image.PreserveAspectCrop
                asynchronous: true
                sourceSize.width: width * Screen.devicePixelRatio
                visible: status === Image.Ready
            }
            ColumnLayout {
                Layout.fillWidth: true
                Layout.alignment: Qt.AlignTop
                Kirigami.Heading {
                    Layout.fillWidth: true
                    text: page.person?.name ?? ""
                    wrapMode: Text.Wrap
                }
                QQC2.Label {
                    Layout.fillWidth: true
                    text: page.person?.lifeSummary ?? ""
                    visible: text.length > 0
                    opacity: 0.7
                    wrapMode: Text.Wrap
                }
                QQC2.Label {
                    id: bio
                    Layout.fillWidth: true
                    property bool expanded: false
                    text: page.person?.bio ?? ""
                    visible: text.length > 0
                    wrapMode: Text.Wrap
                    maximumLineCount: expanded ? 1000 : 8
                    elide: Text.ElideRight
                    TapHandler { onTapped: bio.expanded = !bio.expanded }
                }
            }
        }

        Repeater {
            model: page.person?.credits ?? []
            VideoFlow {
                required property var modelData
                title: page.kindNames[modelData.kind] ?? modelData.kind
                cards: modelData.titles
            }
        }
    }
}
