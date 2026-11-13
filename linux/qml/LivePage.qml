import QtQuick
import QtQuick.Controls as QQC2
import QtQuick.Layouts
import QtQuick.Window
import org.kde.kirigami as Kirigami
import TakeoutFM
import org.kde.ki18n

/**
 * The channels (only when the server has a guide): what's on now on each, or the guide grid
 * with days ahead, as the TV's. In On Now, clicking a channel tunes the live player; in the
 * grid, clicking a program shows it in the detail panel, with Watch Live while it's on and
 * Details for its page. The grid fetches the guide in windows as it scrolls into them, and it
 * all starts over when it gets old.
 */
AppPage {
    id: page

    title: KI18n.i18n("Live")

    property var guide: null
    property var error: null
    property bool loading: false
    property bool grid: false
    /** The server's time, in epoch ms, ticking along. */
    property real now: Date.now()
    property real fetchedAt: 0
    /** The program the detail panel shows ({channel, program}); null: the first channel's on now. */
    property var chosen: null
    /**
     * The keyboard's time in the grid: Up and Down land on whatever overlaps it, so focus
     * doesn't wander as cell widths change. Left and Right move it to the program they reach.
     */
    property real anchor: 0
    readonly property var selection: chosen ?? defaultSelection()

    readonly property real halfHour: 30 * 60 * 1000
    readonly property real hour: 3600 * 1000
    readonly property real day: 24 * hour
    /** Pixels per minute in the grid. */
    readonly property real minute: Kirigami.Units.gridUnit / 4

    actions: [
        Kirigami.Action {
            text: KI18n.i18n("On Now")
            icon.name: "view-list-details"
            checkable: true
            checked: !page.grid
            onTriggered: page.grid = false
        },
        Kirigami.Action {
            text: KI18n.i18n("Guide")
            icon.name: "view-calendar-timeline"
            checkable: true
            checked: page.grid
            onTriggered: {
                page.grid = true
                gridView.forceActiveFocus()
            }
        },
        Kirigami.Action {
            text: KI18n.i18n("Reload")
            icon.name: "view-refresh"
            displayHint: Kirigami.DisplayHint.IconOnly
            onTriggered: page.load()
        }
    ]

    function load() {
        loading = true
        Core.call("guideReload", {}, (g, failure) => {
            loading = false
            if (g) {
                guide = g
                error = null
                fetchedAt = Date.now()
                now = Date.now() + g.skewMs
                gridView.ensureVisible()
            } else if (!guide) {
                error = failure ?? {kind: "network"}
            }
        })
    }

    /** Fetches the guide from [from] to [until] (epoch ms) if it isn't fetched yet. */
    function ensure(from, until) {
        if (!guide) return
        Core.call("guideEnsure", {fromMs: Math.floor(from), untilMs: Math.ceil(until)}, (g) => { if (g) guide = g })
    }

    Component.onCompleted: {
        anchor = now
        load()
    }

    /**
     * Keeps up with the time: the clock, a fresh guide once it's 30 minutes old, the grid's start
     * at now's half hour (a view left in the past comes back to now), the keyboard's time not
     * falling behind, and a chosen program that has ended giving way to what's on now on its
     * channel.
     */
    function catchUp() {
        now = Date.now() + (guide?.skewMs ?? 0)
        if (!loading && Date.now() - fetchedAt > 30 * 60 * 1000) load()
        gridView.catchUp()
        anchor = Math.max(anchor, now)
        if (chosen && chosen.program.endMs <= now) {
            const channel = (guide?.channels ?? []).find(c => c.name === chosen.channel)
            const p = channel ? gridView.focusAt(channel, now) : null
            chosen = p ? {channel: channel.name, program: p} : null
        }
    }

    // on each minute, so the clock and the now line turn over with it
    Timer {
        id: clock
        running: true
        interval: 60000 - page.now % 60000 + 50
        onTriggered: {
            page.catchUp()
            interval = 60000 - page.now % 60000 + 50
            restart()
        }
    }

    // at once when the page comes back, or the app does (a timer doesn't run during suspend)
    onVisibleChanged: if (visible) catchUp()
    Connections {
        target: Qt.application
        function onStateChanged() {
            if (Qt.application.state === Qt.ApplicationActive) page.catchUp()
        }
    }

    function time(ms) {
        return new Date(ms).toLocaleTimeString(Qt.locale(), Locale.ShortFormat)
    }

    /** Local midnight of [ms]'s day. */
    function midnight(ms) {
        const d = new Date(ms)
        d.setHours(0, 0, 0, 0)
        return d.getTime()
    }

    /** "Today", "Tomorrow", "Sat 14". */
    function dayLabel(ms) {
        const days = Math.round((midnight(ms) - midnight(now)) / day)
        if (days === 0) return KI18n.i18n("Today")
        if (days === 1) return KI18n.i18n("Tomorrow")
        const d = new Date(ms)
        return `${Qt.locale().dayName(d.getDay(), Locale.ShortFormat)} ${d.getDate()}`
    }

    function timeLeft(ms) {
        const minutes = Math.max(1, Math.floor(ms / 60000))
        const h = Math.floor(minutes / 60), m = minutes % 60
        return h === 0 ? KI18n.i18n("%1 min left", m)
            : m === 0 ? KI18n.i18n("%1 hr left", h) : KI18n.i18n("%1 hr %2 min left", h, m)
    }

    function programTitle(p) {
        return p.kind === "offair" ? KI18n.i18n("Off Air") : p.title
    }

    function onAt(channel, t) {
        return channel.programs.find(p => t >= p.startMs && t < p.endMs) ?? null
    }

    function nextAfter(channel, t) {
        return channel.programs.find(p => p.startMs > t) ?? null
    }

    function defaultSelection() {
        for (const c of guide?.channels ?? []) {
            const p = onAt(c, now)
            if (p) return {channel: c.name, program: p}
        }
        return null
    }

    function tune(name) {
        applicationWindow().playLive(name)
    }

    function details(p) {
        if (p.videoId) applicationWindow().open("VideoPage", {videoId: p.videoId})
    }

    ColumnLayout {
        spacing: 0

        LoadState {
            Layout.alignment: Qt.AlignHCenter
            loading: page.loading && !page.guide
            error: page.guide ? null : page.error
            onRetry: page.load()
        }

        Kirigami.PlaceholderMessage {
            Layout.fillWidth: true
            visible: !!page.guide && page.guide.channels.length === 0
            text: KI18n.i18n("No channels")
        }

        // ---- On Now ----
        Repeater {
            model: !page.grid ? page.guide?.channels ?? [] : []
            delegate: QQC2.ItemDelegate {
                id: row
                required property var modelData
                readonly property var program: page.onAt(modelData, page.now)
                readonly property var next: page.nextAfter(modelData, page.now)
                Layout.fillWidth: true
                onClicked: page.tune(modelData.name)
                contentItem: ColumnLayout {
                    spacing: Kirigami.Units.smallSpacing
                    QQC2.Label {
                        text: row.modelData.name
                        color: Kirigami.Theme.highlightColor
                        font.bold: true
                    }
                    QQC2.Label {
                        visible: !!row.program && row.program.block !== "" && row.program.kind !== "reserved"
                        text: row.program?.block ?? ""
                        color: Kirigami.Theme.positiveTextColor
                        font: Kirigami.Theme.smallFont
                    }
                    Kirigami.Heading {
                        Layout.fillWidth: true
                        level: 4
                        elide: Text.ElideRight
                        text: !row.program ? KI18n.i18n("Break")
                            : row.program.kind === "episode" && row.program.subtitle
                            ? `${page.programTitle(row.program)} · ${row.program.subtitle}` : page.programTitle(row.program)
                    }
                    QQC2.Label {
                        visible: !!row.program
                        text: row.program ? `${page.time(row.program.startMs)} – ${page.time(row.program.endMs)} · ${page.timeLeft(row.program.endMs - page.now)}` : ""
                        opacity: 0.7
                        font: Kirigami.Theme.smallFont
                    }
                    QQC2.ProgressBar {
                        Layout.fillWidth: true
                        visible: !!row.program
                        from: row.program?.startMs ?? 0
                        to: row.program?.endMs ?? 1
                        value: page.now
                    }
                    QQC2.Label {
                        Layout.fillWidth: true
                        elide: Text.ElideRight
                        opacity: 0.7
                        font: Kirigami.Theme.smallFont
                        text: row.next ? KI18n.i18n("Next: %1 · %2", page.time(row.next.startMs), page.programTitle(row.next))
                                       : KI18n.i18n("Nothing scheduled yet")
                    }
                }
            }
        }

        // ---- Guide: the detail panel ----
        RowLayout {
            id: detail
            readonly property var program: page.selection?.program ?? null
            readonly property bool on: !!program && page.now >= program.startMs && page.now < program.endMs
            visible: page.grid && !!program
            Layout.fillWidth: true
            Layout.margins: Kirigami.Units.largeSpacing
            spacing: Kirigami.Units.largeSpacing * 2

            Image {
                // A fixed size, decoded once at it: a sourceSize that follows the layout as it
                // settles reloads the image at each size, and Qt's threaded scaling has crashed.
                readonly property real artWidth: Kirigami.Units.gridUnit * 14
                Layout.preferredWidth: artWidth
                Layout.preferredHeight: artWidth * 9 / 16
                Layout.alignment: Qt.AlignTop
                source: detail.program ? (detail.program.backdropUri || detail.program.imageUri) : ""
                fillMode: Image.PreserveAspectCrop
                asynchronous: true
                sourceSize.width: artWidth * Screen.devicePixelRatio
                sourceSize.height: artWidth * 9 / 16 * Screen.devicePixelRatio
            }
            ColumnLayout {
                Layout.fillWidth: true
                Layout.alignment: Qt.AlignTop
                spacing: Kirigami.Units.smallSpacing
                QQC2.Label {
                    visible: (detail.program?.block ?? "") !== "" && detail.program.kind !== "reserved"
                    text: detail.program?.block ?? ""
                    color: Kirigami.Theme.positiveTextColor
                }
                Kirigami.Heading {
                    Layout.fillWidth: true
                    level: 2
                    wrapMode: Text.WordWrap
                    maximumLineCount: 2
                    elide: Text.ElideRight
                    text: detail.program ? page.programTitle(detail.program) : ""
                }
                QQC2.Label {
                    Layout.fillWidth: true
                    visible: text !== ""
                    wrapMode: Text.WordWrap
                    maximumLineCount: 2
                    elide: Text.ElideRight
                    text: !detail.program ? ""
                        : detail.program.kind === "reserved" ? (detail.program.subtitle || KI18n.i18n("Programs announced the day before"))
                        : detail.program.subtitle
                }
                QQC2.Label {
                    opacity: 0.7
                    text: !detail.program ? "" : [
                        page.selection.channel,
                        `${page.dayLabel(detail.program.startMs)} ${page.time(detail.program.startMs)} – ${page.time(detail.program.endMs)}`,
                        detail.program.rating,
                        detail.on ? page.timeLeft(detail.program.endMs - page.now) : "",
                    ].filter(s => s).join(" · ")
                }
                RowLayout {
                    QQC2.Button {
                        visible: detail.on && detail.program.videoUri !== ""
                        text: KI18n.i18n("Watch Live")
                        icon.name: "media-playback-start"
                        onClicked: page.tune(page.selection.channel)
                    }
                    QQC2.Button {
                        visible: !!detail.program?.videoId
                        text: KI18n.i18n("Details")
                        icon.name: "documentinfo"
                        onClicked: page.details(detail.program)
                    }
                }
            }
        }

        // ---- Guide: the days ----
        Flow {
            visible: page.grid && !!page.guide
            Layout.fillWidth: true
            Layout.leftMargin: Kirigami.Units.largeSpacing
            Layout.rightMargin: Kirigami.Units.largeSpacing
            Layout.bottomMargin: Kirigami.Units.smallSpacing
            spacing: Kirigami.Units.smallSpacing
            Repeater {
                model: {
                    const last = page.guide ? (page.guide.horizonMs || page.guide.endMs) : page.now
                    const days = []
                    for (let d = page.midnight(page.now); d < last && days.length < 14; d = page.midnight(d + page.day + page.hour * 2)) days.push(d)
                    return days
                }
                delegate: QQC2.Button {
                    required property real modelData
                    text: page.dayLabel(modelData)
                    checkable: true
                    checked: page.midnight(gridView.viewStart) === modelData
                    onClicked: {
                        // this time of day on that day (today: now)
                        gridView.goTo(modelData === page.midnight(page.now) ? page.now
                            : modelData + (page.now - page.midnight(page.now)))
                        gridView.forceActiveFocus()
                    }
                }
            }
        }

        // ---- Guide: the grid ----
        Item {
            id: gridView
            visible: page.grid && !!page.guide
            Layout.fillWidth: true
            /** The grid starts at now's half hour and doesn't go back ([catchUp] moves it on). */
            property real start: 0
            readonly property real end: Math.max(page.guide ? (page.guide.horizonMs || page.guide.endMs + 6 * page.hour) : 0,
                                                 start + page.hour)
            readonly property real nameWidth: Kirigami.Units.gridUnit * 7
            readonly property real rowHeight: Kirigami.Units.gridUnit * 3
            readonly property real rulerHeight: Kirigami.Units.gridUnit * 1.5
            readonly property int count: page.guide?.channels.length ?? 0
            /** The time at the grid's left edge, as it scrolls. */
            readonly property real viewStart: start + across.contentX / page.minute * 60000
            readonly property real viewLength: across.width / page.minute * 60000
            /** What's drawn: the view and an hour either side, moving by half hours. */
            readonly property real drawFrom: Math.max(start, Math.floor(viewStart / page.halfHour) * page.halfHour - page.hour)
            readonly property real drawUntil: drawFrom + viewLength + 3 * page.hour
            // and room for the scroll bar under the last row
            implicitHeight: rulerHeight + rowHeight * count + Kirigami.Units.gridUnit

            function xAt(ms) { return (ms - start) / 60000 * page.minute }

            function scrollTo(ms) {
                const t = Math.floor(ms / page.halfHour) * page.halfHour
                across.contentX = Math.max(0, Math.min(xAt(t), across.contentWidth - across.width))
            }

            function ensureVisible() {
                if (start === 0) start = Math.floor(page.now / page.halfHour) * page.halfHour
                page.ensure(viewStart, viewStart + viewLength + 6 * page.hour)
            }

            onDrawFromChanged: if (page.guide) ensureVisible()

            /** The past drops off: a view still ahead of now stays where it is, one in it comes to now. */
            function catchUp() {
                const half = Math.floor(page.now / page.halfHour) * page.halfHour
                if (start === 0 || half <= start) return
                const ahead = viewStart >= half
                const x = across.contentX - xAt(half)
                start = half
                across.contentX = ahead ? Math.max(0, x) : 0
            }

            // ---- The keyboard, as the TV's remote ----
            activeFocusOnTab: true
            Keys.onPressed: (event) => {
                switch (event.key) {
                case Qt.Key_Left: moveProgram(-1); break
                case Qt.Key_Right: moveProgram(1); break
                case Qt.Key_Up: moveChannel(-1); break
                case Qt.Key_Down: moveChannel(1); break
                case Qt.Key_PageUp: moveDay(-1); break
                case Qt.Key_PageDown: moveDay(1); break
                case Qt.Key_Home: goTo(page.now); break
                case Qt.Key_Return: case Qt.Key_Enter: case Qt.Key_Space: {
                    const p = page.selection?.program
                    if (!p) break
                    if (page.now >= p.startMs && page.now < p.endMs) page.tune(page.selection.channel)
                    else page.details(p)
                    break
                }
                default: return
                }
                event.accepted = true
            }

            function channelIndex() {
                const channels = page.guide?.channels ?? []
                return Math.max(0, channels.findIndex(c => c.name === page.selection?.channel))
            }

            /** On [channel] at [t]: the program on, else the next to start, else the last. */
            function focusAt(channel, t) {
                const ps = channel.programs
                return ps.find(p => t >= p.startMs && t < p.endMs) ?? ps.find(p => p.startMs > t)
                    ?? ps.filter(p => p.endMs <= t).pop() ?? null
            }

            function select(channel, program) {
                if (program) page.chosen = {channel: channel.name, program: program}
            }

            /** Scrolls so [p]'s start (or now, if it's on) is in view. */
            function reveal(p) {
                const from = Math.max(p.startMs, page.now)
                if (from < viewStart) scrollTo(from)
                else if (from >= viewStart + viewLength - page.halfHour) scrollTo(from - page.halfHour)
                revealRow(channelIndex())
            }

            /** Scrolls the page so channel row [i] is in view. */
            function revealRow(i) {
                const f = page.flickable
                const top = gridView.mapToItem(f.contentItem, 0, rulerHeight + i * rowHeight).y
                if (top < f.contentY) f.contentY = top
                else if (top + rowHeight > f.contentY + f.height) f.contentY = top + rowHeight - f.height
            }

            function moveProgram(delta) {
                const channels = page.guide?.channels ?? []
                const channel = channels[channelIndex()]
                const p = page.selection?.program
                if (!channel || !p) return
                const to = delta > 0 ? channel.programs.find(q => q.startMs > p.startMs)
                    // the guide doesn't go back in time
                    : channel.programs.filter(q => q.startMs < p.startMs && q.endMs > page.now).pop()
                if (!to) {
                    // nothing fetched past the edge yet; it's on its way
                    if (delta > 0) page.ensure(p.endMs, p.endMs + 6 * page.hour)
                    return
                }
                select(channel, to)
                page.anchor = Math.max(to.startMs, page.now)
                reveal(to)
            }

            function moveChannel(delta) {
                const channels = page.guide?.channels ?? []
                const i = channelIndex() + delta
                if (i < 0 || i >= channels.length) return
                const p = focusAt(channels[i], Math.max(page.anchor, page.now))
                if (!p) return
                select(channels[i], p)
                // as most guides: the time across stays put, unless the program is out of view
                if (p.endMs <= viewStart || p.startMs >= viewStart + viewLength) reveal(p)
                else revealRow(i)
            }

            /** To [t] on the focused channel: the view there, and the program at it. */
            function goTo(t) {
                const channels = page.guide?.channels ?? []
                const channel = channels[channelIndex()]
                page.anchor = Math.max(t, page.now)
                scrollTo(page.anchor)
                page.ensure(page.anchor, page.anchor + viewLength + 6 * page.hour)
                if (channel) select(channel, focusAt(channel, page.anchor))
            }

            function moveDay(delta) {
                const at = Math.max(page.anchor, page.now)
                const day = page.midnight(page.midnight(at) + delta * page.day + 2 * page.hour)
                const last = page.guide ? (page.guide.horizonMs || page.guide.endMs) : page.now
                if (day < page.midnight(page.now) || day >= last) return
                // this time of day on that day (today: now)
                goTo(day === page.midnight(page.now) ? page.now : day + (page.now - page.midnight(page.now)))
            }

            // the focused program's frame while the grid has the keyboard
            Rectangle {
                id: focusFrame
                visible: gridView.activeFocus && !!page.selection
                z: 10
                color: "transparent"
                border.color: Kirigami.Theme.focusColor
                border.width: 2
                radius: Kirigami.Units.cornerRadius
                readonly property var p: page.selection?.program ?? null
                readonly property real frameLeft: p ? Math.max(gridView.xAt(Math.max(p.startMs, gridView.start)) - across.contentX, 0) : 0
                readonly property real frameRight: p ? Math.min(gridView.xAt(p.endMs) - across.contentX, across.width) : 0
                x: names.width + frameLeft
                y: gridView.rulerHeight + gridView.channelIndex() * gridView.rowHeight + 2
                width: Math.max(frameRight - frameLeft - 2, 0)
                height: gridView.rowHeight - 4
            }

            Column {
                id: names
                width: gridView.nameWidth
                Item { width: 1; height: gridView.rulerHeight }
                Repeater {
                    model: page.grid ? page.guide?.channels ?? [] : []
                    delegate: QQC2.ItemDelegate {
                        required property var modelData
                        width: gridView.nameWidth
                        height: gridView.rowHeight
                        text: modelData.name
                        onClicked: page.tune(modelData.name)
                    }
                }
            }

            Flickable {
                id: across
                anchors { left: names.right; right: parent.right; top: parent.top; bottom: parent.bottom }
                clip: true
                contentWidth: gridView.xAt(gridView.end)
                contentHeight: height
                flickableDirection: Flickable.HorizontalFlick
                boundsBehavior: Flickable.StopAtBounds
                QQC2.ScrollBar.horizontal: QQC2.ScrollBar {}

                // the half hours, and each day's start by name
                Repeater {
                    model: Math.max(0, Math.ceil((gridView.drawUntil - gridView.drawFrom) / page.halfHour))
                    delegate: QQC2.Label {
                        required property int index
                        readonly property real at: gridView.drawFrom + index * page.halfHour
                        x: gridView.xAt(at) + Kirigami.Units.smallSpacing
                        y: Kirigami.Units.smallSpacing
                        text: page.midnight(at) === at ? page.dayLabel(at) : page.time(at)
                        font.bold: page.midnight(at) === at
                        opacity: 0.7
                    }
                }

                Repeater {
                    model: page.grid ? page.guide?.channels ?? [] : []
                    delegate: Item {
                        id: channelRow
                        required property var modelData
                        required property int index
                        y: gridView.rulerHeight + index * gridView.rowHeight
                        width: across.contentWidth
                        height: gridView.rowHeight

                        Repeater {
                            model: channelRow.modelData.music.filter(m => m.endMs > gridView.drawFrom && m.startMs < gridView.drawUntil)
                            delegate: Cell {
                                required property var modelData
                                from: modelData.startMs
                                to: modelData.endMs
                                heading: KI18n.i18n("Music")
                                detail: page.time(modelData.startMs)
                                dim: true
                            }
                        }
                        Repeater {
                            model: channelRow.modelData.programs.filter(p => p.endMs > gridView.drawFrom && p.startMs < gridView.drawUntil)
                            delegate: Cell {
                                required property var modelData
                                from: modelData.startMs
                                to: modelData.endMs
                                heading: (continued ? "‹ " : "") + page.programTitle(modelData)
                                detail: modelData.kind === "reserved" || modelData.kind === "episode" ? modelData.subtitle
                                    : modelData.block !== "" ? modelData.block : page.time(modelData.startMs)
                                special: modelData.block !== ""
                                dim: modelData.kind === "offair" || modelData.endMs <= page.now
                                selected: page.selection?.channel === channelRow.modelData.name
                                    && page.selection?.program.startMs === modelData.startMs
                                onClicked: {
                                    page.chosen = {channel: channelRow.modelData.name, program: modelData}
                                    page.anchor = Math.max(modelData.startMs, page.now)
                                    gridView.forceActiveFocus()
                                }
                                onDoubleClicked: {
                                    if (page.now >= modelData.startMs && page.now < modelData.endMs) page.tune(channelRow.modelData.name)
                                    else page.details(modelData)
                                }
                            }
                        }
                    }
                }

                // now
                Rectangle {
                    visible: page.now >= gridView.start
                    x: gridView.xAt(page.now)
                    width: 2
                    height: gridView.rulerHeight + gridView.rowHeight * gridView.count
                    color: Kirigami.Theme.highlightColor
                }
            }
        }
    }

    /** A cell; one that began before the view's left edge keeps its title in view. */
    component Cell: QQC2.AbstractButton {
        id: cell
        property real from
        property real to
        property string heading
        property string detail
        property bool special: false
        property bool dim: false
        property bool selected: false
        readonly property bool continued: from < gridView.viewStart
        readonly property real startAt: Math.max(from, gridView.start)
        x: gridView.xAt(startAt)
        y: 2
        width: Math.max(gridView.xAt(to) - x - 2, 0)
        height: gridView.rowHeight - 4
        hoverEnabled: true
        QQC2.ToolTip.text: heading
        QQC2.ToolTip.visible: hovered && width < Kirigami.Units.gridUnit * 8
        QQC2.ToolTip.delay: Kirigami.Units.toolTipDelay
        background: Rectangle {
            radius: Kirigami.Units.cornerRadius
            color: cell.selected ? Kirigami.Theme.highlightColor
                : cell.hovered ? Kirigami.Theme.hoverColor
                : cell.special ? Qt.alpha(Kirigami.Theme.highlightColor, 0.25)
                : Qt.alpha(Kirigami.Theme.textColor, cell.dim ? 0.05 : 0.1)
        }
        contentItem: ColumnLayout {
            spacing: 0
            opacity: cell.dim && !cell.selected ? 0.6 : 1
            QQC2.Label {
                Layout.fillWidth: true
                text: cell.heading
                elide: Text.ElideRight
                font.bold: true
                color: cell.selected ? Kirigami.Theme.highlightedTextColor : Kirigami.Theme.textColor
            }
            QQC2.Label {
                Layout.fillWidth: true
                visible: text !== ""
                text: cell.detail
                elide: Text.ElideRight
                opacity: 0.7
                font: Kirigami.Theme.smallFont
                color: cell.selected ? Kirigami.Theme.highlightedTextColor : Kirigami.Theme.textColor
            }
        }
        leftPadding: Kirigami.Units.smallSpacing * 2
            + (continued ? Math.max(0, Math.min(gridView.xAt(gridView.viewStart), gridView.xAt(to - 15 * 60000)) - x) : 0)
        rightPadding: Kirigami.Units.smallSpacing
    }
}
