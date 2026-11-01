import QtQuick
import QtQuick.Controls as QQC2
import QtQuick.Layouts
import org.kde.kirigami as Kirigami
import TakeoutFM.Core

/** Movies: Continue Watching, Recently Added, New Releases, recommendations and genres. */
Kirigami.ScrollablePage {
    id: page

    title: qsTr("Movies")

    property var continueWatching: []
    property var recentlyAdded: []
    property var newReleases: []
    property var recommendations: []
    property var genres: []
    property bool loading: false
    property var error: null

    function load() {
        loading = true
        error = null
        Core.call("continueWatching", {}, (r) => continueWatching = (r ?? []).filter(c => c.id.startsWith("m-")))
        Core.call("videoRecentlyAdded", {}, (r, failure) => {
            loading = false
            error = failure
            recentlyAdded = r ?? []
        })
        Core.call("videoNewReleases", {}, (r) => newReleases = r ?? [])
        if (Core.library.hasRecommendations) Core.call("recommendations", {}, (r) => recommendations = r ?? [])
        Core.call("genres", {}, (r) => genres = r ?? [])
    }
    Component.onCompleted: load()

    Connections {
        target: applicationWindow()
        // Back from the player: Continue Watching has moved.
        function onVideoClosed() {
            Core.call("continueWatching", {}, (r) => page.continueWatching = (r ?? []).filter(c => c.id.startsWith("m-")))
        }
    }

    actions: [
        Kirigami.Action {
            text: qsTr("All Movies")
            icon.name: "view-list-icons"
            onTriggered: applicationWindow().open("VideoGridPage", {title: qsTr("Movies"), call: "movies"})
        },
        Kirigami.Action {
            text: qsTr("Favorites")
            icon.name: "starred-symbolic"
            onTriggered: applicationWindow().open("VideoGridPage", {title: qsTr("Favorite Movies"), call: "videoFavorites", prefix: "m-"})
        }
    ]

    ColumnLayout {
        spacing: Kirigami.Units.largeSpacing

        LoadState {
            Layout.alignment: Qt.AlignHCenter
            loading: page.loading && page.recentlyAdded.length === 0
            error: page.recentlyAdded.length > 0 ? null : page.error
            onRetry: page.load()
        }

        VideoFlow { title: qsTr("Continue Watching"); cards: page.continueWatching }
        VideoFlow { title: qsTr("Recently Added"); cards: page.recentlyAdded; limit: 12 }
        VideoFlow { title: qsTr("New Releases"); cards: page.newReleases; limit: 12 }
        Repeater {
            model: page.recommendations
            VideoFlow {
                required property var modelData
                title: modelData.name
                cards: modelData.titles
                limit: 12
            }
        }

        SectionHeading { text: qsTr("Genres"); visible: page.genres.length > 0 }
        Flow {
            Layout.fillWidth: true
            spacing: Kirigami.Units.smallSpacing
            Repeater {
                model: page.genres
                QQC2.Button {
                    required property var modelData
                    text: modelData.name
                    onClicked: applicationWindow().open("VideoGridPage", {
                        title: modelData.name, call: "genre", args: {genreId: modelData.id}, field: "movies"})
                }
            }
        }
    }
}
