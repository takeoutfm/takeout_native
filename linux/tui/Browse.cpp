// tofm: the sidebar, its pages (lists of rows) and what they open.

#include "Tofm.h"

#include "Player.h"
#include "TakeoutCore.h"
#include "Text.h"

using namespace ftxui;

namespace {
struct SectionInfo {
    const char *name;
};
const SectionInfo Sections[] = {{"Recent"}, {"Recently Added"}, {"New Releases"}, {"Artists"}, {"Favorites"}};
constexpr int SectionCount = 5;
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

bool selectable(const auto &row) { return row.kind != std::decay_t<decltype(row)>::Header; }
}

// ---- Pages ----

std::vector<int> Tofm::Page::visibleRows() const
{
    std::vector<int> shown;
    for (int i = 0; i < int(rows.size()); ++i) {
        const Row &row = rows[i];
        if (filter.isEmpty()) {
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

Tofm::PagePtr Tofm::sectionPage(Section section)
{
    switch (section) {
    case Section::Recent: return recentPage();
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
    load(page, QStringLiteral("artistDetails"), {{"artistId", id}}, [this, id](Page &page, const QVariant &result) {
        const QVariantMap details = result.toMap();
        const QVariantMap artist = details.value("artist").toMap();
        const QString name = artist.value("name").toString();
        page.title = name;
        page.subtitle = artist.value("genre").toString();
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
    load(page, QStringLiteral("albumDetails"), {{"albumId", id}}, [this, id](Page &page, const QVariant &result) {
        const QVariantMap details = result.toMap();
        const QVariantMap album = details.value("album").toMap();
        const QString artist = album.value("artist").toString();
        page.title = album.value("name").toString();
        page.subtitle = joined({artist, album.value("year").toString(), album.value("type").toString()});
        page.rows.push_back({Row::Action, QStringLiteral("▶ Play"), {}, {}, {},
                             [this, id] { playCall(QStringLiteral("albumQueue"), {{"albumId", id}}); }, {}});
        page.rows.push_back({Row::Action, QStringLiteral("⤮ Shuffle"), {}, {}, {},
                             [this, id] { playCall(QStringLiteral("albumQueue"), {{"albumId", id}}, true); }, {}});
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
    // Top bar, page title and subtitle, separator, Now Playing bar (2), key hints.
    return std::max(3, m_screen.dimy() - 8);
}

Element Tofm::renderBrowse()
{
    Element content;
    if (m_nowPlaying) content = renderNowPlaying(pageHeight() + 2);
    else if (Page *page = currentPage()) content = renderPage(*page, pageHeight());
    else content = text("");

    Elements hints = {keyHint("⏎", "open/play"), keyHint("/", "find"), keyHint("␣", "pause"), keyHint("<>", "skip"),
                      keyHint("[]", "seek"), keyHint("a/n", "queue"), keyHint("f", "♥"), keyHint("r", "repeat"),
                      keyHint("p", "playing"), keyHint("q", "quit")};
    if (m_confirmSignOut) hints = {text("Sign out? y: yes · any other key: no") | color(Color::Yellow)};

    return vbox({
        hbox({renderSidebar() | size(WIDTH, EQUAL, SidebarWidth), separator(), content | flex}) | flex,
        separator(),
        renderBar(),
        hbox(hints) | dim,
    });
}

Element Tofm::renderSidebar()
{
    Elements items;
    for (int i = 0; i < SectionCount; ++i) {
        Element item = text(std::string(" ") + Sections[i].name);
        if (i == int(m_section)) item = item | bold | (m_sidebarFocused && !m_nowPlaying ? inverted : color(Color::Cyan));
        items.push_back(item);
    }
    items.push_back(text(""));
    Element nowPlaying = text(" Now Playing");
    if (m_nowPlaying) nowPlaying = nowPlaying | bold | color(Color::Cyan);
    items.push_back(m_player->hasQueue() ? nowPlaying : nowPlaying | dim);
    return vbox(items);
}

Element Tofm::renderPage(const Page &page, int height)
{
    Elements lines;
    lines.push_back(hbox({text(" " + str(page.title)) | bold, filler()}));
    Element subtitle = text(" " + str(page.subtitle)) | dim;
    if (page.filtering || !page.filter.isEmpty()) {
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
        } else {
            const std::string heart = row.etag.isEmpty() ? "  " : favorites.contains(row.etag) ? "♥ " : "  ";
            Element title = text(str(row.title));
            if (row.kind == Row::Action) title = title | color(Color::Cyan);
            // The marks keep their width; a long title or detail gives way.
            line = hbox({text(" " + heart) | color(Color::Red) | size(WIDTH, EQUAL, 3), title | flex_shrink,
                         text(row.detail.isEmpty() ? "" : "  " + str(row.detail)) | dim | flex_shrink, filler(),
                         text(row.right.isEmpty() ? "" : " " + str(row.right) + " ") | dim});
        }
        if (i == page.selected && !m_sidebarFocused) line = line | inverted;
        lines.push_back(line);
    }
    return vbox(lines);
}

// ---- Keys ----

bool Tofm::onBrowseEvent(const Event &event)
{
    if (m_confirmSignOut) {
        if (event == Event::Character('y')) m_core->call("signOut");
        m_confirmSignOut = false;
        redraw();
        return true;
    }
    Page *page = currentPage();
    // Typing a filter takes every key.
    if (page && page->filtering && !m_nowPlaying) return onPageEvent(*page, event);
    if (onPlayerKey(event)) return true;
    if (event == Event::Character('q')) {
        quit();
        return true;
    }
    if (event == Event::Character('S')) {
        m_confirmSignOut = true;
        return true;
    }
    if (m_nowPlaying) return onNowPlayingEvent(event);

    if (m_sidebarFocused) {
        int section = int(m_section);
        if (event == Event::ArrowUp || event == Event::Character('k')) section = std::max(0, section - 1);
        else if (event == Event::ArrowDown || event == Event::Character('j')) section = std::min(SectionCount - 1, section + 1);
        else if (event == Event::ArrowRight || event == Event::Character('l') || event == Event::Return || event == Event::Tab)
            m_sidebarFocused = false;
        if (section != int(m_section)) showSection(Section(section));
        redraw();
        return true;
    }
    if (event == Event::Tab) {
        m_sidebarFocused = true;
        redraw();
        return true;
    }
    return page ? onPageEvent(*page, event) : true;
}

bool Tofm::onPageEvent(Page &page, const Event &event)
{
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
        if (!page.filter.isEmpty()) {
            page.filter.clear();
            selectFirst(page);
        } else {
            back();
        }
    } else if (event == Event::Character('/')) {
        page.filtering = true;
    } else if (event == Event::Character('a') || event == Event::Character('n')) {
        if (row && row->enqueue) row->enqueue(event == Event::Character('a'));
    } else if (event == Event::Character('f')) {
        if (row) toggleFavorite(row->etag);
    } else {
        return false;
    }
    redraw();
    return true;
}
