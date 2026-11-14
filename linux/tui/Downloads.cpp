// tofm: downloads (kept in ~/.local/share/tofm/media by the shared core; Player plays them
// first, so they work offline) and the Downloads page.

#include "Tofm.h"

#include "Player.h"
#include "TakeoutCore.h"
#include "Text.h"

#include <QRegularExpression>

QString Tofm::downloadMark(const QString &etag) const
{
    if (etag.isEmpty()) return {};
    const QVariantMap download = m_core->downloadsByEtag().value(etag).toMap();
    const QString state = download.value("state").toString();
    if (state == "Done") return QStringLiteral("✓");
    if (state == "Downloading") return QStringLiteral("↓ %1%").arg(int(download.value("fraction").toDouble() * 100));
    if (state == "Queued") return QStringLiteral("↓");
    if (state == "Failed") return QStringLiteral("✗");
    return {};
}

void Tofm::downloadCall(const QString &call, const QVariantMap &args, int only)
{
    m_core->call(call, args, [this, only](const QVariant &result, const QVariant &error) {
        const QVariantMap queue = result.toMap();
        const QVariantList items = queue.value("items").toList();
        if (error.isValid() || items.isEmpty() || only >= items.size()) return flash(QStringLiteral("Can't download that"));
        QVariantMap args{{"queue", queue}};
        if (only >= 0) args.insert("only", QVariantList{items[only]});
        m_core->call("downloadQueue", args);
        flash(QStringLiteral("Downloading ") + (only >= 0 ? items[only].toMap().value("title").toString() : queue.value("title").toString()));
    });
}

void Tofm::downloadVideo(const QString &id)
{
    m_core->call("downloadVideo", {{"id", id}, {"check", true}}, [this, id](const QVariant &result, const QVariant &error) {
        const QVariantMap r = result.toMap();
        if (error.isValid()) return flash(QStringLiteral("Can't download that"));
        if (r.value("started").toBool()) return flash(QStringLiteral("Downloading…"));
        const QString need = bytes(r.value("needBytes").toLongLong());
        // Too big for what's free, even with the cache emptied: nothing to ask.
        if (!r.value("fits").toBool())
            return flash(QStringLiteral("Not enough space: it needs %1, more than is free. Remove some downloads first.").arg(need));
        const qint64 freeable = r.value("freeableBytes").toLongLong();
        ask({freeable > 0 ? QStringLiteral("It needs %1 and would leave too little free (clearing the cache frees %2). Download anyway?")
                                .arg(need, bytes(freeable))
                          : QStringLiteral("It needs %1 and would leave too little free. Download anyway?").arg(need),
             {}, false, true, [this, id](const QString &) {
            m_core->call("downloadVideo", {{"id", id}, {"check", true}, {"anyway", true}});
            flash(QStringLiteral("Downloading…"));
        }});
    });
}

void Tofm::removeDownloads(const QStringList &etags, const QString &what)
{
    ask({QStringLiteral("Remove the download of %1?").arg(what), {}, false, true, [this, etags](const QString &) {
        m_core->call("removeDownloads", {{"etags", etags}});
        flash(QStringLiteral("Removed"));
    }});
}

Tofm::Row Tofm::downloadRow(const QStringList &etags, std::function<void()> download)
{
    const QVariantMap downloads = m_core->downloadsByEtag();
    bool all = !etags.isEmpty();
    for (const QString &etag : etags) all = all && downloads.value(etag).toMap().value("state").toString() == "Done";
    Row row{Row::Action};
    if (all) {
        row.title = QStringLiteral("✕ Remove download");
        row.detail = QStringLiteral("on this computer");
        row.activate = [this, etags] { removeDownloads(etags, QStringLiteral("these %1").arg(etags.size())); };
    } else {
        row.title = QStringLiteral("↓ Download");
        row.detail = QStringLiteral("to play offline");
        row.activate = std::move(download);
    }
    return row;
}

void Tofm::refreshDownloads()
{
    if (m_section != Section::Downloads || m_stack.size() != 1) return redraw();
    const int selected = m_stack[0]->selected;
    const int scroll = m_stack[0]->scroll;
    m_stack[0] = downloadsPage();
    m_stack[0]->selected = selected;
    m_stack[0]->scroll = scroll;
    redraw();
}

Tofm::PagePtr Tofm::downloadsPage()
{
    auto page = std::make_shared<Page>();
    page->title = QStringLiteral("Downloads");

    // By the album, playlist, podcast or title they came with, as the KDE app.
    struct Group {
        QString id;
        QString title;
        QVariantList downloads;
    };
    std::vector<Group> groups;
    qint64 total = 0;
    qint64 cached = 0;
    for (const QVariant &value : m_core->downloads()) {
        const QVariantMap entry = value.toMap().value("entry").toMap();
        total += entry.value("bytes").toLongLong();
        if (entry.value("cached").toBool()) cached += entry.value("bytes").toLongLong();
        const QString id = entry.value("group").toString();
        auto group = std::find_if(groups.begin(), groups.end(), [&](const Group &g) { return g.id == id; });
        if (group == groups.end()) {
            groups.push_back({id, entry.value("groupTitle").toString(), {}});
            group = groups.end() - 1;
        }
        group->downloads << value;
    }
    // The largest first, to choose from when space runs short.
    auto size = [](const Group &g) {
        qint64 n = 0;
        for (const QVariant &d : g.downloads) n += d.toMap().value("entry").toMap().value("bytes").toLongLong();
        return n;
    };
    std::sort(groups.begin(), groups.end(), [&](const Group &a, const Group &b) {
        const qint64 sa = size(a), sb = size(b);
        return sa != sb ? sa > sb : a.title.compare(b.title, Qt::CaseInsensitive) < 0;
    });

    if (groups.empty()) {
        page->error = QStringLiteral("Nothing downloaded. ↓ Download on an album, playlist or title, or d on an episode.");
        return page;
    }
    page->subtitle = (cached > 0 ? QStringLiteral("%1 downloaded · %2 cached").arg(bytes(total - cached), bytes(cached)) : bytes(total))
                     + QStringLiteral(" · x: remove · R: retry");
    const QVariantMap usage = m_core->cacheUsage();
    // Downloads are never removed by themselves: say when they fill the disk.
    if (usage.value("lowSpace").toBool())
        page->rows.push_back({Row::Note, QStringLiteral("⚠ Your downloads leave little free space; the largest are listed first.")});
    // Watched movies and episodes, removed only when asked.
    QStringList watched;
    qint64 watchedBytes = 0;
    for (const QVariant &etag : usage.value("watched").toList()) {
        const QVariantMap entry = m_core->downloadsByEtag().value(etag.toString()).toMap().value("entry").toMap();
        if (entry.isEmpty()) continue;
        watched << etag.toString();
        watchedBytes += entry.value("bytes").toLongLong();
    }
    if (!watched.isEmpty()) {
        page->rows.push_back({Row::Action, QStringLiteral("✕ Remove watched: %1 · %2").arg(watched.size()).arg(bytes(watchedBytes)),
                              QStringLiteral("movies and episodes watched to the end"), {}, {}, [this, watched] {
            removeDownloads(watched, QStringLiteral("%1 watched").arg(watched.size()));
        }, {}});
    }
    page->rows.push_back({Row::Action, QStringLiteral("✕ Remove all downloads"), {}, {}, {}, [this] {
        ask({QStringLiteral("Remove every download?"), {}, false, true, [this](const QString &) {
            m_core->call("removeAllDownloads");
            flash(QStringLiteral("Removed"));
        }});
    }, {}});

    for (const Group &group : groups) {
        page->rows.push_back({Row::Header, group.title});
        // What plays: the group's finished music and episodes, in order.
        QVariantList items;
        QStringList playable;
        for (const QVariant &value : group.downloads) {
            const QVariantMap download = value.toMap();
            const QVariantMap entry = download.value("entry").toMap();
            if (entry.value("video").toBool() || download.value("state").toString() != "Done") continue;
            playable << entry.value("etag").toString();
            items << QVariantMap{{"title", entry.value("title")}, {"artist", entry.value("artist")}, {"album", entry.value("album")},
                                 {"imageUri", entry.value("imageUri")}, {"backgroundUri", QString()}, {"uri", entry.value("location")},
                                 {"etag", entry.value("etag")}, {"podcast", entry.value("podcast")}};
        }
        const QVariantMap queue{{"title", group.title}, {"creator", QString()},
                                {"imageUri", items.isEmpty() ? QVariant() : items[0].toMap().value("imageUri")},
                                {"items", items}, {"startIndex", 0}, {"startPositionMs", 0}, {"location", QString()}};
        for (const QVariant &value : group.downloads) {
            const QVariantMap entry = value.toMap().value("entry").toMap();
            const QString etag = entry.value("etag").toString();
            Row row;
            row.title = entry.value("title").toString();
            // kept by Playback Caching: may go to stay within the cache size
            row.detail = entry.value("cached").toBool()
                ? QStringList{QStringLiteral("cached"), entry.value("artist").toString()}.filter(QRegularExpression(".")).join(QStringLiteral(" · "))
                : entry.value("artist").toString();
            row.etag = etag;
            if (entry.value("video").toBool()) {
                const QString id = entry.value("group").toString();
                row.activate = [this, id] { playVideo(id, false); };
            } else {
                const int start = int(playable.indexOf(etag));
                row.activate = [this, queue, start] {
                    if (start < 0) return flash(QStringLiteral("Not downloaded yet"));
                    m_player->play(queue, false, start);
                };
            }
            page->rows.push_back(row);
        }
    }
    for (int i = 0; i < int(page->rows.size()); ++i) {
        if (page->rows[i].kind == Row::Item) {
            page->selected = i;
            break;
        }
    }
    return page;
}
