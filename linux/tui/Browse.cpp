// tofm: the sidebar, its pages (lists of rows) and what they open.

#include "Tofm.h"

#include "Cover.h"
#include "Player.h"
#include "TakeoutCore.h"
#include "Text.h"

using namespace ftxui;

namespace {
constexpr int SidebarWidth = 18;

QString ago(qint64 ms)
{
    const qint64 minutes = (QDateTime::currentMSecsSinceEpoch() - ms) / 60000;
    if (minutes < 1) return QStringLiteral("just now");
    if (minutes < 60) return QStringLiteral("%1m ago").arg(minutes);
    if (minutes < 24 * 60) return QStringLiteral("%1h ago").arg(minutes / 60);
    if (minutes < 30 * 24 * 60) return QStringLiteral("%1d ago").arg(minutes / (24 * 60));
    return QDateTime::fromMSecsSinceEpoch(ms).toString(QStringLiteral("yyyy-MM-dd"));
}

QString joined(const QStringList &parts, const QString &separator = QStringLiteral(" · "))
{
    QStringList kept;
    for (const QString &part : parts)
        if (!part.isEmpty()) kept << part;
    return kept.join(separator);
}

bool selectable(const auto &row)
{
    using Row = std::decay_t<decltype(row)>;
    return row.kind != Row::Header && row.kind != Row::Note;
}
}

// ---- Pages ----

std::vector<int> Tofm::Page::visibleRows() const
{
    std::vector<int> shown;
    for (int i = 0; i < int(rows.size()); ++i) {
        const Row &row = rows[i];
        if (filter.isEmpty() || search) {
            shown.push_back(i);
        } else if (row.kind == Row::Item
                   && (row.title.contains(filter, Qt::CaseInsensitive) || row.detail.contains(filter, Qt::CaseInsensitive))) {
            shown.push_back(i);
        }
    }
    return shown;
}

namespace {
/** Moves [page]'s selection [delta] rows, skipping headings; stays put at the ends. */
void moveSelection(auto &page, int delta)
{
    const std::vector<int> shown = page.visibleRows();
    if (shown.empty()) return;
    int at = std::clamp(page.selected, 0, int(shown.size()) - 1);
    const int step = delta > 0 ? 1 : -1;
    int moved = 0;
    for (int i = at + step; i >= 0 && i < int(shown.size()) && moved != delta; i += step) {
        if (selectable(page.rows[shown[i]])) {
            at = i;
            moved += step;
        }
    }
    page.selected = at;
}

/** The first selectable row, after (re)loading or filtering. */
void selectFirst(auto &page)
{
    const std::vector<int> shown = page.visibleRows();
    page.selected = 0;
    page.scroll = 0;
    for (int i = 0; i < int(shown.size()); ++i) {
        if (selectable(page.rows[shown[i]])) {
            page.selected = i;
            return;
        }
    }
}
}

Tofm::Page *Tofm::currentPage() { return m_stack.empty() ? nullptr : m_stack.back().get(); }

void Tofm::showSection(Section section)
{
    m_section = section;
    m_stack.clear();
    m_stack.push_back(sectionPage(section));
    redraw();
}

void Tofm::push(const PagePtr &page)
{
    m_stack.push_back(page);
    m_sidebarFocused = false;
    redraw();
}

void Tofm::back()
{
    if (m_stack.size() > 1) m_stack.pop_back();
    else m_sidebarFocused = true;
    redraw();
}

std::vector<Tofm::Section> Tofm::sections() const
{
    // What the server has, as the KDE app's sidebar (music unless it says otherwise).
    const QVariantMap library = m_core->library();
    const bool music = library.value("hasMusic", true).toBool();
    std::vector<Section> shown = {Section::Recent, Section::Search};
    if (music) {
        for (Section section : {Section::RecentlyAdded, Section::NewReleases, Section::Artists, Section::Favorites})
            shown.push_back(section);
        if (library.value("hasPlaylists", true).toBool()) shown.push_back(Section::Playlists);
        shown.push_back(Section::Radio);
    }
    if (library.value("hasPodcasts").toBool()) shown.push_back(Section::Podcasts);
    if (library.value("hasMovies").toBool()) shown.push_back(Section::Movies);
    if (library.value("hasShows").toBool()) shown.push_back(Section::Shows);
    shown.push_back(Section::Downloads);
    shown.push_back(Section::Settings);
    return shown;
}

namespace {
const char *sectionName(int section)
{
    static const char *const Names[] = {"Recent", "Search", "Recently Added", "New Releases", "Artists", "Favorites",
                                        "Playlists", "Radio", "Podcasts", "Movies", "TV Shows", "Downloads", "Settings"};
    return Names[section];
}
}

Tofm::PagePtr Tofm::sectionPage(Section section)
{
    switch (section) {
    case Section::Recent: return recentPage();
    case Section::Search: return searchPage();
    case Section::Playlists: return playlistsPage();
    case Section::Radio: return radioPage();
    case Section::Podcasts: return podcastsPage();
    case Section::Movies: return videoListPage(QStringLiteral("Movies"), QStringLiteral("movies"));
    case Section::Shows: return videoListPage(QStringLiteral("TV Shows"), QStringLiteral("shows"));
    case Section::Downloads: return downloadsPage();
    case Section::Settings: return settingsPage();
    case Section::RecentlyAdded: return albumListPage(QStringLiteral("Recently Added"), QStringLiteral("recentlyAdded"));
    case Section::NewReleases: return albumListPage(QStringLiteral("New Releases"), QStringLiteral("newReleases"));
    case Section::Artists: return artistsPage();
    case Section::Favorites: return favoritesPage();
    }
    return recentPage();
}

void Tofm::load(const PagePtr &page, const QString &call, const QVariantMap &args,
                std::function<void(Page &, const QVariant &)> fill)
{
    page->loading = true;
    std::weak_ptr<Page> weak = page;
    m_core->call(call, args, [this, weak, fill](const QVariant &result, const QVariant &error) {
        const PagePtr page = weak.lock();
        if (!page) return; // navigated away
        page->loading = false;
        if (error.isValid()) {
            page->error = error.toMap().value("kind").toString() == "network"
                ? QStringLiteral("Can't reach the server.")
                : QStringLiteral("Couldn't load this: %1").arg(error.toMap().value("message").toString());
        } else {
            fill(*page, result);
            selectFirst(*page);
        }
        redraw();
    });
}

Tofm::PagePtr Tofm::recentPage()
{
    auto page = std::make_shared<Page>();
    page->title = QStringLiteral("Recently Played");
    const QVariantList entries = m_core->recentlyPlayed();
    for (const QVariant &value : entries) {
        const QVariantMap entry = value.toMap();
        const QVariantList items = entry.value("items").toList();
        const QString creator = entry.value("creator").toString();
        const QString tracks = items.size() == 1 ? QStringLiteral("1 track") : QStringLiteral("%1 tracks").arg(items.size());
        Row row;
        row.title = entry.value("title").toString();
        row.detail = creator.isEmpty() || creator == row.title ? tracks : creator + " · " + tracks;
        row.right = ago(entry.value("playedAt").toLongLong());
        row.activate = [this, entry, items] {
            // Played again from where it got to, as the other apps' Recently Played.
            QVariantMap queue{
                {"title", entry.value("title")},
                {"creator", entry.value("creator")},
                {"imageUri", entry.value("imageUri")},
                {"items", items},
                {"startIndex", std::clamp(entry.value("index").toInt(), 0, std::max(0, int(items.size()) - 1))},
                {"startPositionMs", entry.value("positionMs")},
                {"location", entry.value("location")},
            };
            m_player->play(queue);
        };
        page->rows.push_back(row);
    }
    if (entries.isEmpty()) page->error = QStringLiteral("Nothing played yet. Pick an album from the sidebar.");
    selectFirst(*page);
    return page;
}

Tofm::PagePtr Tofm::albumListPage(const QString &title, const QString &call)
{
    auto page = std::make_shared<Page>();
    page->title = title;
    load(page, call, {}, [this](Page &page, const QVariant &result) {
        for (const QVariant &album : result.toList()) page.rows.push_back(albumRow(album.toMap(), true));
    });
    return page;
}

Tofm::PagePtr Tofm::artistsPage()
{
    auto page = std::make_shared<Page>();
    page->title = QStringLiteral("Artists");
    load(page, QStringLiteral("artists"), {}, [this](Page &page, const QVariant &result) {
        for (const QVariant &artist : result.toList()) page.rows.push_back(artistRow(artist.toMap()));
        page.subtitle = QStringLiteral("%1 artists · / to find one").arg(page.rows.size());
    });
    return page;
}

Tofm::PagePtr Tofm::favoritesPage()
{
    auto page = std::make_shared<Page>();
    page->title = QStringLiteral("Favorites");
    load(page, QStringLiteral("favorites"), {}, [this](Page &page, const QVariant &result) {
        const QVariantMap favorites = result.toMap();
        const QVariantList artists = favorites.value("artists").toList();
        const QVariantList tracks = favorites.value("tracks").toList();
        if (!tracks.isEmpty()) {
            page.rows.push_back({Row::Action, QStringLiteral("▶ Play favorite tracks"), {}, {}, {},
                                 [this] { playCall(QStringLiteral("favoritesQueue"), {{"startIndex", 0}}); }, {}});
        }
        if (!artists.isEmpty()) page.rows.push_back({Row::Header, QStringLiteral("Artists")});
        for (const QVariant &artist : artists) page.rows.push_back(artistRow(artist.toMap()));
        if (!tracks.isEmpty()) page.rows.push_back({Row::Header, QStringLiteral("Tracks")});
        for (int i = 0; i < tracks.size(); ++i) {
            const QVariantMap track = tracks[i].toMap();
            page.rows.push_back(trackRow(track, joined({track.value("artist").toString(), track.value("album").toString()}),
                                         [this, i] { playCall(QStringLiteral("favoritesQueue"), {{"startIndex", i}}); }));
        }
        if (artists.isEmpty() && tracks.isEmpty()) page.error = QStringLiteral("No favorites yet: f on a track adds it.");
    });
    return page;
}

Tofm::PagePtr Tofm::artistPage(const QVariantMap &artist)
{
    auto page = std::make_shared<Page>();
    const QString id = artist.value("id").toString();
    page->title = artist.value("name").toString();
    page->subtitle = artist.value("genre").toString();
    page->coverUri = artist.value("imageUri").toString();
    load(page, QStringLiteral("artistDetails"), {{"artistId", id}}, [this, id](Page &page, const QVariant &result) {
        const QVariantMap details = result.toMap();
        const QVariantMap artist = details.value("artist").toMap();
        const QString name = artist.value("name").toString();
        page.title = name;
        page.subtitle = artist.value("genre").toString();
        page.coverUri = artist.value("imageUri").toString();
        auto mix = [this, id](const char *mix) {
            return [this, id, mix] { playCall(QStringLiteral("artistQueue"), {{"artistId", id}, {"mix", mix}}); };
        };
        page.rows.push_back({Row::Action, QStringLiteral("▶ Popular"), {}, {}, {}, mix("Popular"), {}});
        page.rows.push_back({Row::Action, QStringLiteral("⤮ Shuffle"), {}, {}, {}, mix("Shuffle"), {}});
        page.rows.push_back({Row::Action, QStringLiteral("≋ Radio"), QStringLiteral("this artist and similar ones"), {}, {}, mix("Radio"), {}});
        const QString arid = artist.value("arid").toString();
        if (!arid.isEmpty()) {
            const bool favorite = details.value("isFavorite").toBool();
            page.rows.push_back({Row::Action, favorite ? QStringLiteral("♥ Favorite artist") : QStringLiteral("♡ Add to favorites"),
                                 {}, {}, {}, [this, arid, favorite, artist] {
                m_core->call("setArtistFavorite", {{"arid", arid}, {"favorite", !favorite}},
                             [this, favorite, artist](const QVariant &, const QVariant &error) {
                    if (error.isValid()) return;
                    flash(favorite ? QStringLiteral("Removed from favorites") : QStringLiteral("Added to favorites"));
                    // Reload, to show the new state.
                    if (!m_stack.empty()) {
                        const int selected = m_stack.back()->selected;
                        m_stack.back() = artistPage(artist);
                        m_stack.back()->selected = selected;
                    }
                });
            }, {}});
        }
        const QVariantList popular = details.value("popular").toList().mid(0, 5);
        if (!popular.isEmpty()) page.rows.push_back({Row::Header, QStringLiteral("Popular")});
        for (int i = 0; i < popular.size(); ++i) {
            const QVariantMap track = popular[i].toMap();
            page.rows.push_back(trackRow(track, track.value("album").toString(), [this, name, popular, i] {
                playCall(QStringLiteral("tracksQueue"), {{"title", name}, {"tracks", popular}, {"startIndex", i}});
            }));
        }
        for (auto [key, heading] : {std::pair{"albums", "Albums"}, {"singles", "Singles & EPs"}, {"otherReleases", "Other releases"}}) {
            const QVariantList albums = details.value(key).toList();
            if (!albums.isEmpty()) page.rows.push_back({Row::Header, QString::fromUtf8(heading)});
            for (const QVariant &album : albums) page.rows.push_back(albumRow(album.toMap(), false));
        }
        const QVariantList similar = details.value("similar").toList();
        if (!similar.isEmpty()) page.rows.push_back({Row::Header, QStringLiteral("Similar artists")});
        for (const QVariant &other : similar) page.rows.push_back(artistRow(other.toMap()));
    });
    return page;
}

Tofm::PagePtr Tofm::albumPage(const QVariantMap &album)
{
    auto page = std::make_shared<Page>();
    const QString id = album.value("id").toString();
    page->title = album.value("name").toString();
    page->subtitle = joined({album.value("artist").toString(), album.value("year").toString(), album.value("type").toString()});
    page->coverUri = album.value("coverUri").toString();
    load(page, QStringLiteral("albumDetails"), {{"albumId", id}}, [this, id](Page &page, const QVariant &result) {
        const QVariantMap details = result.toMap();
        const QVariantMap album = details.value("album").toMap();
        const QString artist = album.value("artist").toString();
        page.title = album.value("name").toString();
        page.subtitle = joined({artist, album.value("year").toString(), album.value("type").toString()});
        page.coverUri = album.value("coverUri").toString();
        page.rows.push_back({Row::Action, QStringLiteral("▶ Play"), {}, {}, {},
                             [this, id] { playCall(QStringLiteral("albumQueue"), {{"albumId", id}}); }, {}});
        page.rows.push_back({Row::Action, QStringLiteral("⤮ Shuffle"), {}, {}, {},
                             [this, id] { playCall(QStringLiteral("albumQueue"), {{"albumId", id}}, true); }, {}});
        QStringList etags;
        for (const QVariant &track : details.value("tracks").toList()) etags << track.toMap().value("etag").toString();
        etags.removeAll(QString());
        page.rows.push_back(downloadRow(etags, [this, id] { downloadCall(QStringLiteral("albumQueue"), {{"albumId", id}}); }));
        const QString artistId = details.value("artistId").toString();
        if (!artistId.isEmpty()) {
            page.rows.push_back({Row::Action, QStringLiteral("→ ") + artist, QStringLiteral("the artist"), {}, {},
                                 [this, artistId, artist] { push(artistPage({{"id", artistId}, {"name", artist}})); }, {}});
        }
        const QVariantList tracks = details.value("tracks").toList();
        const bool discs = details.value("discCount").toInt() > 1;
        int disc = 0;
        if (!discs) page.rows.push_back({Row::Header, QStringLiteral("Tracks")});
        for (int i = 0; i < tracks.size(); ++i) {
            const QVariantMap track = tracks[i].toMap();
            if (discs && track.value("discNum").toInt() != disc) {
                disc = track.value("discNum").toInt();
                page.rows.push_back({Row::Header, QStringLiteral("Disc %1").arg(disc)});
            }
            const QString by = track.value("artist").toString();
            Row row = trackRow(track, by != artist ? by : QString(),
                               [this, id, i] { playCall(QStringLiteral("albumQueue"), {{"albumId", id}, {"startIndex", i}}); });
            row.title = QStringLiteral("%1  %2").arg(track.value("trackNum").toInt(), 2).arg(row.title);
            page.rows.push_back(row);
        }
        const QVariantList similar = details.value("similar").toList();
        if (!similar.isEmpty()) page.rows.push_back({Row::Header, QStringLiteral("Similar albums")});
        for (const QVariant &other : similar) page.rows.push_back(albumRow(other.toMap(), true));
    });
    return page;
}

Tofm::Row Tofm::albumRow(const QVariantMap &album, bool showArtist)
{
    Row row;
    row.title = album.value("name").toString();
    row.detail = showArtist ? album.value("artist").toString() : QString();
    row.right = album.value("year").toString();
    row.activate = [this, album] { push(albumPage(album)); };
    return row;
}

Tofm::Row Tofm::artistRow(const QVariantMap &artist)
{
    Row row;
    row.title = artist.value("name").toString();
    row.detail = artist.value("genre").toString();
    row.activate = [this, artist] { push(artistPage(artist)); };
    return row;
}

Tofm::Row Tofm::trackRow(const QVariantMap &track, const QString &detail, std::function<void()> play)
{
    Row row;
    row.title = track.value("title").toString();
    row.detail = detail;
    row.etag = track.value("etag").toString();
    row.activate = std::move(play);
    const QString id = track.value("id").toString();
    row.enqueue = [this, id](bool last) { enqueueTrack(id, last); };
    return row;
}

void Tofm::playCall(const QString &call, const QVariantMap &args, bool shuffled)
{
    m_core->call(call, args, [this, shuffled](const QVariant &queue, const QVariant &error) {
        if (!error.isValid() && !queue.toMap().value("items").toList().isEmpty()) {
            m_player->play(queue.toMap(), shuffled);
        } else if (error.toMap().value("kind").toString() != "network") {
            flash(QStringLiteral("Can't play that"));
        }
    });
}

void Tofm::enqueueTrack(const QString &trackId, bool last)
{
    // The track alone, from its play queue (which goes on with similar tracks).
    m_core->call("trackQueue", {{"trackId", trackId}}, [this, last](const QVariant &queue, const QVariant &) {
        const QVariantList items = queue.toMap().value("items").toList();
        if (items.isEmpty()) return flash(QStringLiteral("Couldn't add that"));
        m_player->enqueue({items.first()}, last);
        flash(last ? QStringLiteral("Added to the queue") : QStringLiteral("Playing next"));
    });
}

void Tofm::toggleFavorite(const QString &etag)
{
    if (etag.isEmpty()) return;
    const bool favorite = m_core->favoriteTracks().contains(etag);
    m_core->call("setTrackFavorite", {{"etag", etag}, {"favorite", !favorite}});
    flash(favorite ? QStringLiteral("Removed from favorites") : QStringLiteral("Added to favorites"));
}

// ---- Drawing ----

int Tofm::pageHeight() const
{
    // Top bar, page title and subtitle, separator, Now Playing bar (3), key hints.
    return std::max(3, m_screen.dimy() - 9);
}

Element Tofm::renderBrowse()
{
    Element content;
    if (m_help) content = renderHelp();
    else if (m_nowPlaying) content = renderNowPlaying(pageHeight() + 2);
    else if (Page *page = currentPage()) content = renderPage(*page, pageHeight());
    else content = text("");

    Elements hints = {keyHint("⏎", "open/play"), keyHint("/", "find"), keyHint("␣", "pause"), keyHint("<>", "skip"),
                      keyHint("[]", "seek"), keyHint("a/n", "queue"), keyHint("f", "♥"), keyHint("r", "repeat"),
                      keyHint("p", "playing"), keyHint("?", "help"), keyHint("q", "quit")};

    return vbox({
        hbox({renderSidebar() | size(WIDTH, EQUAL, SidebarWidth), separator(), content | flex}) | flex,
        separator(),
        renderBar(),
        m_prompt ? renderPrompt() : hbox(hints) | dim,
    });
}

Element Tofm::renderSidebar()
{
    Elements items;
    Section previous = Section::Recent;
    for (Section section : sections()) {
        // Gaps between the groups: history and search, music, podcasts and video.
        if ((section == Section::RecentlyAdded && previous != section) || (section == Section::Podcasts)
            || (section == Section::Movies && previous != Section::Podcasts) || section == Section::Downloads)
            items.push_back(text(""));
        Element item = text(std::string(" ") + sectionName(int(section)));
        if (section == m_section && !m_nowPlaying) item = item | bold | (m_sidebarFocused ? inverted : color(Color::Cyan));
        items.push_back(item);
        previous = section;
    }
    items.push_back(text(""));
    Element nowPlaying = text(" Now Playing");
    if (m_nowPlaying) nowPlaying = nowPlaying | bold | (m_sidebarFocused ? inverted : color(Color::Cyan));
    items.push_back(m_player->hasQueue() ? nowPlaying : nowPlaying | dim);
    if (m_video.process) items.push_back(text(" ▶ in mpv") | color(Color::Green));
    return vbox(items);
}

Element Tofm::renderPage(const Page &page, int height)
{
    // An album's or artist's cover beside its title; the rows below get less room.
    if (!page.coverUri.isEmpty() && art() != Art::Off && height > 14) {
        // About 40% of the page's height, at least 6 rows, so it grows with the window.
        const int CoverRows = std::clamp(height * 2 / 5, 6, 18);
        Page rest = page;
        rest.coverUri.clear();
        rest.title.clear();
        rest.subtitle.clear();
        Element list = renderPage(rest, height - CoverRows + 1);
        // The copy's scroll is the one that counts.
        const_cast<Page &>(page).scroll = rest.scroll;
        Element head = hbox({text(" "), m_cover->render(page.coverUri, CoverRows), text(" "),
                             vbox({text(str(page.title)) | bold, text(str(page.subtitle)) | dim, filler()}) | flex});
        return vbox({head, list});
    }
    Elements lines;
    if (!page.title.isEmpty() || !page.subtitle.isEmpty() || page.search || page.filtering || !page.filter.isEmpty())
        lines.push_back(hbox({text(" " + str(page.title)) | bold, filler()}));
    Element subtitle = text(" " + str(page.subtitle)) | dim;
    if (page.search) {
        subtitle = hbox({text(" Search: ") | bold, text(str(page.filter)), page.filtering ? text("▏") | blink : text(""),
                         text(page.filtering ? "   Enter: search · Esc: done" : "   / or s: new search") | dim});
    } else if (page.filtering || !page.filter.isEmpty()) {
        subtitle = hbox({text(" / ") | bold, text(str(page.filter)), page.filtering ? text("▏") | blink : text(""),
                         text(page.filtering ? "   Enter: done · Esc: clear" : "   Esc: clear") | dim});
    }
    lines.push_back(subtitle);

    if (page.loading) {
        lines.push_back(text(" Loading…") | dim);
        return vbox(lines);
    }
    if (!page.error.isEmpty() && page.rows.empty()) {
        lines.push_back(text(" " + str(page.error)) | dim);
        return vbox(lines);
    }

    const std::vector<int> shown = page.visibleRows();
    if (shown.empty()) lines.push_back(text(" Nothing matches.") | dim);
    // Keep the selection in view (a const page draws; scrolling is remembered on the page).
    auto &scroll = const_cast<Page &>(page).scroll;
    if (page.selected < scroll) scroll = page.selected;
    if (page.selected >= scroll + height) scroll = page.selected - height + 1;
    scroll = std::clamp(scroll, 0, std::max(0, int(shown.size()) - height));

    const QStringList favorites = m_core->favoriteTracks();
    for (int i = scroll; i < std::min(int(shown.size()), scroll + height); ++i) {
        const Row &row = page.rows[shown[i]];
        Element line;
        if (row.kind == Row::Header) {
            line = text(" " + str(row.title)) | bold | color(Color::Yellow);
        } else if (row.kind == Row::Note) {
            line = text("   " + str(row.title)) | dim;
        } else {
            const std::string heart = favorites.contains(row.etag) && !row.etag.isEmpty() ? "♥ " : "  ";
            Element heartMark = text(" " + heart) | size(WIDTH, EQUAL, 3);
            if (heart != "  ") heartMark = heartMark | color(Color::Red); // else selecting the row would show a red block
            const QString mark = downloadMark(row.etag);
            // Fitted by hand: the marks always show, the title gets what's left, the detail the rest.
            const QString right = row.right.isEmpty() ? QString() : " " + row.right + " ";
            const QString markText = mark.isEmpty() ? QString() : mark + " ";
            const int room = std::max(10, m_screen.dimx() - 19 - 3 - int(right.size()) - int(markText.size()) - 1);
            auto cut = [](const QString &text, int width) {
                return text.size() <= width ? text : width <= 1 ? QString() : text.left(width - 1) + QStringLiteral("…");
            };
            const QString titleText = cut(row.title, room);
            const QString detailText = row.detail.isEmpty() ? QString() : cut("  " + row.detail, room - int(titleText.size()));
            Element title = text(str(titleText));
            if (row.kind == Row::Action) title = title | color(Color::Cyan);
            line = hbox({heartMark, title, text(str(detailText)) | dim, filler(), text(str(right)) | dim,
                         text(str(markText)) | color(mark == "✗" ? Color::Red : Color::Green)});
        }
        if (i == page.selected && !m_sidebarFocused) line = line | inverted;
        lines.push_back(line);
    }
    return vbox(lines);
}

// ---- Keys ----

bool Tofm::onBrowseEvent(const Event &event)
{
    if (m_prompt) return onPromptEvent(event);
    Page *page = currentPage();
    // Typing a filter takes every key.
    if (page && page->filtering && !m_nowPlaying) return onPageEvent(*page, event);
    if (onPlayerKey(event)) return true;
    if (event == Event::Character('q')) {
        quit();
        return true;
    }
    if (event == Event::Character('S')) {
        ask({QStringLiteral("Sign out of %1?").arg(m_core->endpoint()), {}, false, true,
             [this](const QString &) { m_core->call("signOut"); }});
        return true;
    }
    if (event == Event::Character('?')) {
        m_help = !m_help;
        redraw();
        return true;
    }
    if (m_help) {
        if (event == Event::Escape || event == Event::Character('q') || event == Event::ArrowLeft) m_help = false;
        redraw();
        return true;
    }
    if (m_sidebarFocused) {
        // The sections, then Now Playing.
        const std::vector<Section> shown = sections();
        auto at = std::find(shown.begin(), shown.end(), m_section);
        const int nowPlaying = int(shown.size());
        int index = m_nowPlaying ? nowPlaying : at == shown.end() ? 0 : int(at - shown.begin());
        const int before = index;
        if (event == Event::ArrowUp || event == Event::Character('k')) index = std::max(0, index - 1);
        else if (event == Event::ArrowDown || event == Event::Character('j')) index = std::min(nowPlaying, index + 1);
        else if (event == Event::ArrowRight || event == Event::Character('l') || event == Event::Return || event == Event::Tab)
            m_sidebarFocused = false;
        if (index != before) {
            m_nowPlaying = index == nowPlaying;
            if (m_nowPlaying) m_queueSelected = std::max(0, m_player->index());
            else showSection(shown[index]);
        }
        // Search starts typing straight away.
        if (!m_sidebarFocused && !m_nowPlaying && m_section == Section::Search && page && page->rows.empty()) page->filtering = true;
        redraw();
        return true;
    }
    if (event == Event::Tab) {
        m_sidebarFocused = true;
        redraw();
        return true;
    }
    if (m_nowPlaying) return onNowPlayingEvent(event);
    return page ? onPageEvent(*page, event) : true;
}

bool Tofm::onPageEvent(Page &page, const Event &event)
{
    if (page.filtering && page.search) {
        if (event == Event::Return) {
            page.filtering = false;
            if (!page.filter.trimmed().isEmpty()) page.search(page.filter.trimmed());
        } else if (event == Event::Escape) {
            page.filtering = false;
        } else if (event == Event::Backspace) {
            page.filter.chop(1);
        } else if (event.is_character()) {
            page.filter += QString::fromStdString(event.character());
        }
        redraw();
        return true;
    }
    if (page.filtering) {
        if (event == Event::Return) {
            page.filtering = false;
        } else if (event == Event::Escape) {
            page.filtering = false;
            page.filter.clear();
            selectFirst(page);
        } else if (event == Event::Backspace) {
            page.filter.chop(1);
            selectFirst(page);
        } else if (event == Event::ArrowDown || event == Event::ArrowUp) {
            moveSelection(page, event == Event::ArrowDown ? 1 : -1);
        } else if (event.is_character()) {
            page.filter += QString::fromStdString(event.character());
            selectFirst(page);
        }
        redraw();
        return true;
    }

    const std::vector<int> shown = page.visibleRows();
    const Row *row = shown.empty() || page.selected >= int(shown.size()) ? nullptr : &page.rows[shown[page.selected]];
    const int height = pageHeight();
    if (event == Event::ArrowDown || event == Event::Character('j')) moveSelection(page, 1);
    else if (event == Event::ArrowUp || event == Event::Character('k')) moveSelection(page, -1);
    else if (event == Event::PageDown) moveSelection(page, height - 1);
    else if (event == Event::PageUp) moveSelection(page, -(height - 1));
    else if (event == Event::Home || event == Event::Character('g')) moveSelection(page, -int(shown.size()));
    else if (event == Event::End || event == Event::Character('G')) moveSelection(page, int(shown.size()));
    else if (wheel(event)) moveSelection(page, 3 * wheel(event));
    else if (event == Event::Return || event == Event::ArrowRight || event == Event::Character('l')) {
        if (row && row->activate) row->activate();
    } else if (event == Event::ArrowLeft || event == Event::Character('h') || event == Event::Backspace) {
        back();
    } else if (event == Event::Escape) {
        if (!page.filter.isEmpty() && !page.search) {
            page.filter.clear();
            selectFirst(page);
        } else {
            back();
        }
    } else if (event == Event::Character('/') || (page.search && event == Event::Character('s'))) {
        page.filtering = true;
    } else if (event == Event::Character('a') || event == Event::Character('n')) {
        if (row && row->enqueue) row->enqueue(event == Event::Character('a'));
    } else if (event == Event::Character('f')) {
        if (row && row->enqueue) toggleFavorite(row->etag); // tracks only

    } else if (event == Event::Character('d')) {
        if (row && row->download) row->download();
    } else if (event == Event::Character('x')) {
        if (row && !downloadMark(row->etag).isEmpty()) removeDownloads({row->etag}, row->title);
    } else if (event == Event::Character('R')) {
        if (row && downloadMark(row->etag) == "✗") {
            m_core->call("retryDownload", {{"etag", row->etag}});
            flash(QStringLiteral("Trying again"));
        }
    } else {
        return false;
    }
    redraw();
    return true;
}
