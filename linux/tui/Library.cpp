// tofm: search, playlists, radio, podcasts, and the movie / TV pages (videos play in mpv,
// Video.cpp).

#include "Tofm.h"

#include "Player.h"
#include "TakeoutCore.h"
#include "Text.h"

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
    if (kind == "Stream") return QStringLiteral("Streams");
    if (kind == "Genre") return QStringLiteral("Genres");
    if (kind == "Period") return QStringLiteral("Decades");
    if (kind == "Artist") return QStringLiteral("Artist radio");
    if (kind == "Similar") return QStringLiteral("Similar");
    if (kind == "Series") return QStringLiteral("Series");
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
        for (const QVariant &value : result.toList()) {
            const QVariantMap playlist = value.toMap();
            Row row;
            row.title = playlist.value("name").toString();
            row.detail = count(playlist.value("trackCount").toInt(), QStringLiteral("1 track"), QStringLiteral("%1 tracks"));
            row.activate = [this, playlist] { push(playlistPage(playlist)); };
            page.rows.push_back(row);
        }
        if (page.rows.empty()) page.error = QStringLiteral("No playlists yet.");
    });
    return page;
}

Tofm::PagePtr Tofm::playlistPage(const QVariantMap &playlist)
{
    auto page = std::make_shared<Page>();
    page->title = playlist.value("name").toString();
    load(page, QStringLiteral("playlistQueue"), {{"playlistId", playlist.value("id")}}, [this](Page &page, const QVariant &result) {
        const QVariantMap queue = result.toMap();
        const QVariantList items = queue.value("items").toList();
        page.subtitle = count(items.size(), QStringLiteral("1 track"), QStringLiteral("%1 tracks"));
        page.rows.push_back({Row::Action, QStringLiteral("▶ Play"), {}, {}, {}, [this, queue] { m_player->play(queue); }, {}});
        page.rows.push_back({Row::Action, QStringLiteral("⤮ Shuffle"), {}, {}, {}, [this, queue] { m_player->play(queue, true); }, {}});
        page.rows.push_back({Row::Header, QStringLiteral("Tracks")});
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
    row.activate = [this, id] { playCall(QStringLiteral("stationQueue"), {{"stationId", id}}); };
    return row;
}

Tofm::PagePtr Tofm::radioPage()
{
    auto page = std::make_shared<Page>();
    page->title = QStringLiteral("Radio");
    load(page, QStringLiteral("radio"), {}, [this](Page &page, const QVariant &result) {
        for (const QVariant &value : result.toList()) {
            const QVariantMap group = value.toMap();
            const QVariantList stations = group.value("stations").toList();
            if (stations.isEmpty()) continue;
            page.rows.push_back({Row::Header, stationHeading(group.value("kind").toString())});
            for (const QVariant &station : stations) page.rows.push_back(stationRow(station.toMap()));
        }
        if (page.rows.empty()) page.error = QStringLiteral("No stations.");
    });
    return page;
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
        page.rows.push_back({Row::Header, QStringLiteral("Episodes")});
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
            row.activate = [this, series, episodes, i] {
                playCall(QStringLiteral("episodesQueue"), {{"title", series.value("title")}, {"imageUri", series.value("imageUri")},
                                                           {"episodes", episodes}, {"startIndex", i}});
            };
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
            page.rows.push_back({Row::Action, target.isEmpty() ? QStringLiteral("▶ Play") : QStringLiteral("▶ Play ") + target,
                                 QStringLiteral("in mpv, from where it was left"), {}, {}, [this, id] { playVideo(id, false); }, {}});
            if (kind != "Series") {
                page.rows.push_back({Row::Action, QStringLiteral("↺ Start over"), {}, {}, {}, [this, id] { playVideo(id, true); }, {}});
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
