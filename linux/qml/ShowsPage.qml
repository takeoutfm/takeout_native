import QtQuick
import QtQuick.Controls as QQC2
import QtQuick.Layouts
import org.kde.kirigami as Kirigami
import TakeoutFM.Core

/** TV: episodes to continue, recently updated series, then every series. */
Kirigami.ScrollablePage {
    id: page

    title: qsTr("TV")

    property var continueWatching: []
    property var recent: []
    property var shows: []
    property bool loading: false
    property var error: null

    function loadContinue() {
        Core.call("continueWatching", {}, (r) => continueWatching = (r ?? []).filter(c => !c.id.startsWith("m-")))
    }

    function load() {
        loading = true
        error = null
        loadContinue()
        Core.call("recentShows", {}, (r) => recent = r ?? [])
        Core.call("shows", {}, (r, failure) => {
            loading = false
            error = failure
            shows = r ?? []
        })
    }
    Component.onCompleted: load()

    Connections {
        target: applicationWindow()
        function onVideoClosed() { page.loadContinue() }
    }

    actions: [
        Kirigami.Action {
            text: qsTr("Favorites")
            icon.name: "starred-symbolic"
            onTriggered: applicationWindow().open("VideoGridPage", {title: qsTr("Favorite Shows"), call: "videoFavorites", prefix: "s-"})
        }
    ]

    ColumnLayout {
        spacing: Kirigami.Units.largeSpacing

        LoadState {
            Layout.alignment: Qt.AlignHCenter
            loading: page.loading && page.shows.length === 0
            error: page.shows.length > 0 ? null : page.error
            onRetry: page.load()
        }

        VideoFlow { title: qsTr("Continue Watching"); cards: page.continueWatching; wide: true }
        VideoFlow { title: qsTr("Recently Updated"); cards: page.recent; limit: 12 }
        VideoFlow { title: qsTr("All Shows"); cards: page.shows }
    }
}
