// tofm: search, playlists, radio, podcasts, and the movie / TV pages (videos play in mpv,
// Video.cpp).

#include "Tofm.h"

#include "Player.h"
#include "TakeoutCore.h"
#include "Text.h"

#include <QDateTime>
#include <QFileInfo>
#include <QProcess>
#include <QTimer>

#include <algorithm>

namespace {
QString joined(const QStringList &parts, const QString &separator = QStringLiteral(" · "))
{
    QStringList kept;
    for (const QString &part : parts)
        if (!part.isEmpty()) kept << part;
    return kept.join(separator);
}

QString count(int n, const QString &one, const QString &many) { return n == 1 ? one : many.arg(n); }

/** [text] in lines of at most [width] characters, at spaces (a description as note rows). */
QStringList wrap(const QString &text, int width = 76)
{
    QStringList lines;
    QString line;
    for (const QString &word : text.simplified().split(' ', Qt::SkipEmptyParts)) {
        if (!line.isEmpty() && line.size() + 1 + word.size() > width) {
            lines << line;
            line.clear();
        }
        line += (line.isEmpty() ? QString() : QStringLiteral(" ")) + word;
    }
    if (!line.isEmpty()) lines << line;
    return lines;
}

QString stationHeading(const QString &kind)
{
    if (kind == "Live") return QStringLiteral("Live");
    if (kind == "Stream") return QStringLiteral("Streams");
    return QStringLiteral("More");
}
}

// ---- Search ----

Tofm::PagePtr Tofm::searchPage()
{
    auto page = std::make_shared<Page>();
    page->title = QStringLiteral("Search");
    std::weak_ptr<Page> weak = page;
    page->search = [this, weak](const QString &query) {
        if (const PagePtr page = weak.lock()) runSearch(page, query);
    };
    page->error = QStringLiteral("Type and press Enter. The server's syntax works: artist:Beck, guitar:\"Jack White\"…");
    return page;
}

void Tofm::runSearch(const PagePtr &page, const QString &query)
{
    // Music and video at once; the page fills when both have answered.
    struct Answers {
        QVariantMap music;
        QVariantMap video;
        int waiting = 2;
    };
    auto answers = std::make_shared<Answers>();
    page->rows.clear();
    page->error.clear();
    page->loading = true;
    std::weak_ptr<Page> weak = page;
    auto done = [this, weak, answers, query] {
        if (--answers->waiting > 0) return;
        const PagePtr page = weak.lock();
        if (!page) return;
        page->loading = false;
        const QVariantMap &music = answers->music;
        const QVariantMap &video = answers->video;
        const QVariantList artists = music.value("artists").toList();
        const QVariantList albums = music.value("albums").toList();
        const QVariantList tracks = music.value("tracks").toList();
        const QVariantList stations = music.value("stations").toList();
        if (!artists.isEmpty()) page->rows.push_back({Row::Header, QStringLiteral("Artists")});
        for (const QVariant &artist : artists) page->rows.push_back(artistRow(artist.toMap()));
        if (!albums.isEmpty()) page->rows.push_back({Row::Header, QStringLiteral("Albums")});
        for (const QVariant &album : albums) page->rows.push_back(albumRow(album.toMap(), true));
        if (!tracks.isEmpty()) page->rows.push_back({Row::Header, QStringLiteral("Tracks")});
        for (int i = 0; i < tracks.size(); ++i) {
            const QVariantMap track = tracks[i].toMap();
            page->rows.push_back(trackRow(track, joined({track.value("artist").toString(), track.value("album").toString()}),
                                          [this, tracks, i, query] {
                playCall(QStringLiteral("tracksQueue"), {{"title", query}, {"tracks", tracks}, {"startIndex", i}});
            }));
        }
        if (!stations.isEmpty()) page->rows.push_back({Row::Header, QStringLiteral("Stations")});
        for (const QVariant &station : stations) page->rows.push_back(stationRow(station.toMap()));
        for (auto [key, heading] : {std::pair{"movies", "Movies"}, {"shows", "TV Shows"}, {"episodes", "Episodes"}}) {
            const QVariantList cards = video.value(key).toList();
            if (!cards.isEmpty()) page->rows.push_back({Row::Header, QString::fromUtf8(heading)});
            for (const QVariant &card : cards) page->rows.push_back(videoRow(card.toMap()));
        }
        if (page->rows.empty()) page->error = QStringLiteral("Nothing found for “%1”.").arg(query);
        page->selected = 0;
        page->scroll = 0;
        for (int i = 0; i < int(page->rows.size()); ++i) {
            if (page->rows[i].kind != Row::Header) {
                page->selected = i;
                break;
            }
        }
        redraw();
    };
    const QVariantMap library = m_core->library();
    const bool video = library.value("hasMovies").toBool() || library.value("hasShows").toBool();
    m_core->call("search", {{"query", query}}, [answers, done](const QVariant &result, const QVariant &) {
        answers->music = result.toMap();
        done();
    });
    if (video) {
        m_core->call("videoSearch", {{"query", query}}, [answers, done](const QVariant &result, const QVariant &) {
            answers->video = result.toMap();
            done();
        });
    } else {
        done();
    }
    redraw();
}

// ---- Playlists and radio ----

Tofm::PagePtr Tofm::playlistsPage()
{
    auto page = std::make_shared<Page>();
    page->title = QStringLiteral("Playlists");
    load(page, QStringLiteral("playlists"), {}, [this](Page &page, const QVariant &result) {
        page.rows.push_back({Row::Action, QStringLiteral("+ New playlist…"), {}, {}, {}, [this] {
            ask({QStringLiteral("New playlist's name:"), {}, false, false, [this](const QString &name) {
                if (name.trimmed().isEmpty()) return;
                m_core->call("createPlaylist", {{"name", name.trimmed()}}, [this](const QVariant &playlist, const QVariant &error) {
                    if (error.isValid()) return flash(QStringLiteral("Couldn't make that playlist"));
                    flash(QStringLiteral("Made ") + playlist.toMap().value("name").toString());
                });
            }});
        }, {}});
        for (const QVariant &value : result.toList()) {
            const QVariantMap playlist = value.toMap();
            Row row;
            row.title = playlist.value("name").toString();
            row.detail = count(playlist.value("trackCount").toInt(), QStringLiteral("1 track"), QStringLiteral("%1 tracks"));
            row.activate = [this, playlist] { push(playlistPage(playlist)); };
            page.rows.push_back(row);
        }
        if (page.rows.size() == 1) page.rows.push_back({Row::Note, QStringLiteral("No playlists yet.")});
    });
    return page;
}

void Tofm::reloadPlaylist(const QVariant &id, const QString &name)
{
    for (PagePtr &page : m_stack) {
        if (page->playlistId != id) continue;
        const int selected = page->selected;
        page = playlistPage({{"id", id}, {"name", name}});
        page->selected = selected;
    }
    redraw();
}

void Tofm::addToPlaylist(const QString &trackId, const QString &title)
{
    // A page of the playlists to pick from, with a new one first; Enter adds and goes back.
    auto page = std::make_shared<Page>();
    page->title = QStringLiteral("Add to a playlist");
    page->subtitle = title;
    auto added = [this](const QString &name) {
        back();
        flash(QStringLiteral("Added to ") + name);
    };
    load(page, QStringLiteral("playlists"), {}, [this, trackId, added](Page &page, const QVariant &result) {
        page.rows.push_back({Row::Action, QStringLiteral("+ New playlist…"), {}, {}, {}, [this, trackId, added] {
            ask({QStringLiteral("New playlist's name:"), {}, false, false, [this, trackId, added](const QString &name) {
                if (name.trimmed().isEmpty()) return;
                m_core->call("createPlaylist", {{"name", name.trimmed()}}, [this, trackId, added](const QVariant &playlist, const QVariant &error) {
                    if (error.isValid()) return flash(QStringLiteral("Couldn't make that playlist"));
                    const QVariantMap p = playlist.toMap();
                    m_core->call("addTrackToPlaylist", {{"playlistId", p.value("id")}, {"trackId", trackId}},
                                 [this, p, added](const QVariant &, const QVariant &error) {
                        if (error.isValid()) return flash(QStringLiteral("Couldn't add that"));
                        added(p.value("name").toString());
                    });
                });
            }});
        }, {}});
        for (const QVariant &value : result.toList()) {
            const QVariantMap playlist = value.toMap();
            Row row;
            row.title = playlist.value("name").toString();
            row.detail = count(playlist.value("trackCount").toInt(), QStringLiteral("1 track"), QStringLiteral("%1 tracks"));
            row.activate = [this, playlist, trackId, added] {
                m_core->call("addTrackToPlaylist", {{"playlistId", playlist.value("id")}, {"trackId", trackId}},
                             [this, playlist, added](const QVariant &, const QVariant &error) {
                    if (error.isValid()) return flash(QStringLiteral("Couldn't add that"));
                    added(playlist.value("name").toString());
                });
            };
            page.rows.push_back(row);
        }
    });
    push(page);
}

Tofm::PagePtr Tofm::playlistPage(const QVariantMap &playlist)
{
    auto page = std::make_shared<Page>();
    page->title = playlist.value("name").toString();
    const QVariant id = playlist.value("id");
    page->playlistId = id;
    const QString name = page->title;
    load(page, QStringLiteral("playlistQueue"), {{"playlistId", id}}, [this, id, name](Page &page, const QVariant &result) {
        const QVariantMap queue = result.toMap();
        const QVariantList items = queue.value("items").toList();
        page.subtitle = count(items.size(), QStringLiteral("1 track"), QStringLiteral("%1 tracks"));
        page.rows.push_back({Row::Action, QStringLiteral("▶ Play"), {}, {}, {}, [this, queue] { m_player->play(queue); }, {}});
        page.rows.push_back({Row::Action, QStringLiteral("⤮ Shuffle"), {}, {}, {}, [this, queue] { m_player->play(queue, true); }, {}});
        QStringList etags;
        for (const QVariant &item : items) etags << item.toMap().value("etag").toString();
        etags.removeAll(QString());
        page.rows.push_back(downloadRow(etags, [this, queue] {
            m_core->call("downloadQueue", {{"queue", queue}});
            flash(QStringLiteral("Downloading ") + queue.value("title").toString());
        }));
        page.rows.push_back({Row::Action, QStringLiteral("✕ Delete playlist…"), {}, {}, {}, [this, id, name] {
            ask({QStringLiteral("Delete the playlist “%1”? Its tracks stay in your library.").arg(name), {}, false, true,
                 [this, id, name](const QString &) {
                m_core->call("deletePlaylist", {{"playlistId", id}}, [this, name](const QVariant &, const QVariant &error) {
                    if (error.isValid()) return flash(QStringLiteral("Couldn't delete ") + name);
                    flash(QStringLiteral("Deleted ") + name);
                    back();
                });
            }});
        }, {}});
        page.rows.push_back({Row::Header, QStringLiteral("Tracks · D: remove from the playlist")});
        for (int i = 0; i < items.size(); ++i) {
            const QVariantMap item = items[i].toMap();
            Row row;
            row.title = item.value("title").toString();
            row.detail = joined({item.value("artist").toString(), item.value("album").toString()});
            row.etag = item.value("etag").toString();
            row.activate = [this, queue, i] { m_player->play(queue, false, i); };
            row.enqueue = [this, item](bool last) {
                m_player->enqueue({item}, last);
                flash(last ? QStringLiteral("Added to the queue") : QStringLiteral("Playing next"));
            };
            // LIB-13: by its place in the playlist.
            const QString title = row.title;
            row.remove = [this, id, name, i, title] {
                ask({QStringLiteral("Remove “%1” from %2?").arg(title, name), {}, false, true, [this, id, name, i, title](const QString &) {
                    m_core->call("removeFromPlaylist", {{"playlistId", id}, {"index", i}},
                                 [this, id, name, title](const QVariant &, const QVariant &error) {
                        if (error.isValid()) return flash(QStringLiteral("Couldn't remove ") + title);
                        flash(QStringLiteral("Removed ") + title);
                        reloadPlaylist(id, name);
                    });
                }});
            };
            page.rows.push_back(row);
        }
    });
    return page;
}

Tofm::Row Tofm::stationRow(const QVariantMap &station)
{
    Row row;
    row.title = station.value("name").toString();
    row.detail = station.value("description").toString();
    const QString id = station.value("id").toString();
    row.stationId = id;
    row.activate = [this, id] { playCall(QStringLiteral("stationQueue"), {{"stationId", id}}); };
    return row;
}

Tofm::PagePtr Tofm::radioPage()
{
    auto page = std::make_shared<Page>();
    page->title = QStringLiteral("Radio");
    std::weak_ptr<Page> weak = page;
    load(page, QStringLiteral("radio"), {}, [this, weak](Page &page, const QVariant &result) {
        for (const QVariant &value : result.toList()) {
            const QVariantMap group = value.toMap();
            const QVariantList stations = group.value("stations").toList();
            if (stations.isEmpty()) continue;
            page.rows.push_back({Row::Header, stationHeading(group.value("kind").toString())});
            for (const QVariant &station : stations) page.rows.push_back(stationRow(station.toMap()));
        }
        if (page.rows.empty()) page.error = QStringLiteral("No stations.");
        radioNow(weak);
    });
    return page;
}

void Tofm::radioNow(const std::weak_ptr<Page> &weak)
{
    m_core->call(QStringLiteral("radioNow"), {}, [this, weak](const QVariant &result, const QVariant &) {
        const PagePtr page = weak.lock();
        if (!page) return; // navigated away
        const QVariantMap now = result.toMap();
        const QVariantMap stations = now.value("stations").toMap();
        // Each live station's row: what's on ("Artist – Title"), with the show while one is on.
        for (Row &row : page->rows) {
            const QVariantMap on = stations.value(row.stationId).toMap();
            if (on.isEmpty()) continue;
            const QVariantMap track = on.value("track").toMap();
            const QString show = on.value("show").toMap().value("name").toString();
            const QString artist = track.value("artist").toString();
            const QString line = artist.isEmpty() ? track.value("title").toString()
                                                  : artist + QStringLiteral(" – ") + track.value("title").toString();
            row.detail = show.isEmpty() ? line : show + QStringLiteral(" · ") + line;
        }
        redraw();
        // Again when the first of those tracks ends (a minute on after a failure), for as long
        // as the page is kept; only asked while it's the one on screen.
        const qint64 at = now.value("refreshAtMs").toLongLong();
        const qint64 wait = at > 0 ? at - QDateTime::currentMSecsSinceEpoch() + 500 : 60000;
        QTimer::singleShot(int(std::clamp<qint64>(wait, 2000, 600000)), m_core, [this, weak] {
            const PagePtr page = weak.lock();
            if (!page) return;
            if (currentPage() == page.get()) radioNow(weak);
            else QTimer::singleShot(60000, m_core, [this, weak] { radioNow(weak); });
        });
    });
}

// ---- Podcasts ----

Tofm::PagePtr Tofm::podcastsPage()
{
    auto page = std::make_shared<Page>();
    page->title = QStringLiteral("Podcasts");
    load(page, QStringLiteral("podcasts"), {}, [this](Page &page, const QVariant &result) {
        for (const QVariant &value : result.toList()) {
            const QVariantMap series = value.toMap();
            Row row;
            row.title = series.value("title").toString();
            row.detail = series.value("author").toString();
            row.activate = [this, series] { push(podcastPage(series)); };
            page.rows.push_back(row);
        }
        page.subtitle = QStringLiteral("/ to find one");
        if (page.rows.empty()) page.error = QStringLiteral("No podcasts.");
    });
    return page;
}

Tofm::PagePtr Tofm::podcastPage(const QVariantMap &series)
{
    auto page = std::make_shared<Page>();
    const QString id = series.value("id").toString();
    page->title = series.value("title").toString();
    page->subtitle = series.value("author").toString();
    load(page, QStringLiteral("podcastDetails"), {{"seriesId", id}}, [this, id](Page &page, const QVariant &result) {
        const QVariantMap details = result.toMap();
        const QVariantMap series = details.value("series").toMap();
        const QVariantList episodes = details.value("episodes").toList();
        const bool subscribed = details.value("subscribed").toBool();
        page.title = series.value("title").toString();
        page.subtitle = joined({series.value("author").toString(),
                                count(episodes.size(), QStringLiteral("1 episode"), QStringLiteral("%1 episodes"))});
        page.rows.push_back({Row::Action, subscribed ? QStringLiteral("✓ Subscribed") : QStringLiteral("+ Subscribe"),
                             subscribed ? QStringLiteral("Enter to unsubscribe") : QString(), {}, {},
                             [this, id, subscribed, series] {
            m_core->call("setSubscribed", {{"seriesId", id}, {"subscribed", !subscribed}},
                         [this, subscribed, series](const QVariant &, const QVariant &error) {
                if (error.isValid()) return;
                flash(subscribed ? QStringLiteral("Unsubscribed") : QStringLiteral("Subscribed"));
                if (!m_stack.empty()) m_stack.back() = podcastPage(series);
            });
        }, {}});
        for (const QString &line : wrap(series.value("description").toString()).mid(0, 3))
            page.rows.push_back({Row::Note, line});
        page.rows.push_back({Row::Header, QStringLiteral("Episodes · d: download · m: played")});
        for (int i = 0; i < episodes.size(); ++i) {
            const QVariantMap episode = episodes[i].toMap();
            const QVariantMap progress = episode.value("progress").toMap();
            const QString state = progress.value("type").toString();
            Row row;
            row.title = episode.value("title").toString();
            row.detail = episode.value("date").toString().left(10);
            row.right = state == "Complete" ? QStringLiteral("✓")
                : state == "Started" ? QStringLiteral("%1m left").arg(progress.value("minutesLeft").toInt())
                                     : QString();
            const QVariantMap args{{"title", series.value("title")}, {"imageUri", series.value("imageUri")},
                                   {"episodes", episodes}, {"startIndex", i}};
            row.activate = [this, args] { playCall(QStringLiteral("episodesQueue"), args); };
            row.download = [this, args, i] { downloadCall(QStringLiteral("episodesQueue"), args, i); };
            // POD-7: played, or not, without playing it.
            const QString eid = episode.value("eid").toString();
            const bool played = state == "Complete";
            row.mark = [this, eid, played, series] {
                m_core->call("markPlayed", {{"eid", eid}, {"played", !played}}, [this, played, series](const QVariant &, const QVariant &error) {
                    if (error.isValid()) return flash(QStringLiteral("Couldn't mark that"));
                    flash(played ? QStringLiteral("Marked not played") : QStringLiteral("Marked played"));
                    for (PagePtr &page : m_stack) {
                        if (page->title != series.value("title").toString()) continue;
                        const int selected = page->selected;
                        page = podcastPage(series);
                        page->selected = selected;
                    }
                    redraw();
                });
            };
            // Its download is keyed by the GUID (eid), as its queue item's etag. Not a track:
            // no ♥ (only tracks can be favorites).
            row.etag = episode.value("eid").toString();
            page.rows.push_back(row);
        }
    });
    return page;
}

// ---- Movies and TV ----

Tofm::Row Tofm::videoRow(const QVariantMap &card)
{
    Row row;
    row.title = card.value("title").toString();
    const QVariant progress = card.value("progress");
    if (progress.isValid() && !progress.isNull()) row.right = QStringLiteral("%1%").arg(int(progress.toDouble() * 100));
    row.activate = [this, card] { push(videoPage(card)); };
    return row;
}

Tofm::PagePtr Tofm::videoListPage(const QString &title, const QString &call)
{
    auto page = std::make_shared<Page>();
    page->title = title;
    load(page, call, {}, [this](Page &page, const QVariant &result) {
        for (const QVariant &card : result.toList()) page.rows.push_back(videoRow(card.toMap()));
        page.subtitle = QStringLiteral("%1 · / to find one").arg(count(int(page.rows.size()), QStringLiteral("1 title"), QStringLiteral("%1 titles")));
    });
    return page;
}

Tofm::PagePtr Tofm::videoPage(const QVariantMap &card)
{
    auto page = std::make_shared<Page>();
    const QString id = card.value("id").toString();
    page->title = card.value("title").toString();
    load(page, QStringLiteral("videoDetails"), {{"id", id}}, [this, id](Page &page, const QVariant &result) {
        const QVariantMap d = result.toMap();
        const QString kind = d.value("kind").toString();
        const QVariantMap episode = d.value("episode").toMap();
        page.title = kind == "Episode"
            ? QStringLiteral("%1 · S%2E%3 %4").arg(episode.value("seriesTitle").toString()).arg(episode.value("season").toInt())
                  .arg(episode.value("episode").toInt()).arg(episode.value("name").toString())
            : d.value("title").toString();
        const int minutes = d.value("runtimeMinutes").toInt();
        const QString length = kind == "Series"
            ? joined({count(d.value("seasonCount").toInt(), QStringLiteral("1 season"), QStringLiteral("%1 seasons")),
                      count(d.value("episodeCount").toInt(), QStringLiteral("1 episode"), QStringLiteral("%1 episodes"))})
            : minutes > 0 ? QStringLiteral("%1h %2m").arg(minutes / 60).arg(minutes % 60) : QString();
        page.subtitle = joined({d.value("year").toString(), d.value("rating").toString(), length,
                                d.value("genres").toStringList().join(", ")});

        if (!d.value("videoUri").toString().isEmpty()) {
            const QString target = d.value("playTarget").toString();
            const QStringList command = QProcess::splitCommand(m_prefs.value("videoPlayer").toString().trimmed());
            const QString player = command.isEmpty() ? QStringLiteral("mpv") : QFileInfo(command.first()).fileName();
            page.rows.push_back({Row::Action, target.isEmpty() ? QStringLiteral("▶ Play") : QStringLiteral("▶ Play ") + target,
                                 player == "mpv" ? QStringLiteral("in mpv, from where it was left")
                                                 : QStringLiteral("in %1 (downloads only)").arg(player),
                                 {}, {}, [this, id] { playVideo(id, false); }, {}});
            if (kind != "Series") {
                page.rows.push_back({Row::Action, QStringLiteral("↺ Start over"), {}, {}, {}, [this, id] { playVideo(id, true); }, {}});
                const QString etag = d.value("etag").toString();
                page.rows.push_back(downloadRow(etag.isEmpty() ? QStringList() : QStringList{etag}, [this, id] { downloadVideo(id); }));
            }
        }
        if (kind == "Episode" && !episode.value("seriesId").toString().isEmpty()) {
            const QVariantMap series{{"id", episode.value("seriesId")}, {"title", episode.value("seriesTitle")}};
            page.rows.push_back({Row::Action, QStringLiteral("→ ") + episode.value("seriesTitle").toString(), QStringLiteral("the series"),
                                 {}, {}, [this, series] { push(videoPage(series)); }, {}});
        }
        if (!d.value("tagline").toString().isEmpty()) page.rows.push_back({Row::Note, d.value("tagline").toString()});
        for (const QString &line : wrap(d.value("description").toString()).mid(0, 6)) page.rows.push_back({Row::Note, line});
        const QStringList directors = d.value("directors").toStringList();
        if (!directors.isEmpty()) page.rows.push_back({Row::Note, QStringLiteral("Directed by ") + directors.join(", ")});

        for (const QVariant &value : d.value("seasons").toList()) {
            const QVariantMap season = value.toMap();
            page.rows.push_back({Row::Header, QStringLiteral("Season %1").arg(season.value("number").toInt())});
            for (const QVariant &e : season.value("episodes").toList()) {
                const QVariantMap info = e.toMap();
                Row row;
                row.title = QStringLiteral("%1  %2").arg(info.value("episode").toInt(), 2).arg(info.value("name").toString());
                if (info.value("runtimeMinutes").toInt() > 0) row.right = QStringLiteral("%1m").arg(info.value("runtimeMinutes").toInt());
                const QVariantMap card{{"id", info.value("id")}, {"title", info.value("name")}};
                row.activate = [this, card] { push(videoPage(card)); };
                page.rows.push_back(row);
            }
        }
        const QVariantList similar = d.value("similar").toList();
        if (!similar.isEmpty()) page.rows.push_back({Row::Header, QStringLiteral("Similar")});
        for (const QVariant &other : similar) page.rows.push_back(videoRow(other.toMap()));
    });
    return page;
}
