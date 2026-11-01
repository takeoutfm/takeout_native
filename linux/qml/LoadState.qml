import QtQuick
import QtQuick.Controls as QQC2
import org.kde.kirigami as Kirigami

/** A spinner while [loading], or [error] (a failed call's) with Retry. */
Item {
    id: state

    property bool loading: false
    property var error: null
    signal retry()

    visible: loading || !!error
    implicitWidth: Kirigami.Units.gridUnit * 20
    implicitHeight: Kirigami.Units.gridUnit * 10

    QQC2.BusyIndicator {
        anchors.centerIn: parent
        running: state.loading
        visible: running
    }

    Kirigami.PlaceholderMessage {
        anchors.centerIn: parent
        width: parent.width - Kirigami.Units.gridUnit * 2
        visible: !state.loading && !!state.error
        icon.name: state.error?.kind === "network" ? "network-disconnect" : "dialog-warning"
        text: state.error?.kind === "network" ? qsTr("Can't reach the server")
            : state.error?.status === 404 ? qsTr("Not found")
            : qsTr("Something went wrong")
        explanation: state.error?.kind === "network" ? "" : (state.error?.message ?? "")
        helpfulAction: Kirigami.Action {
            text: qsTr("Retry")
            icon.name: "view-refresh"
            onTriggered: state.retry()
        }
    }
}
