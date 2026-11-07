// tofm: the Now Playing bar under every page, the full Now Playing view with the queue, and
// the playback keys that work everywhere.

#include "Tofm.h"

#include "Cover.h"
#include "Player.h"
#include "TakeoutCore.h"
#include "Text.h"

using namespace ftxui;

namespace {
/** Columns [text] takes, counting each character as one (titles are mostly Latin). */
int columns(const QString &text) { return int(text.toUcs4().size()); }
}

QString Tofm::marquee(const QString &text, int width) const
{
    const QList<uint> chars = text.toUcs4();
    if (width <= 0 || chars.size() <= width) return text;
    // As the other apps: wait at the start, then slide at about 7 columns a second with a
    // second copy following after a gap, and repeat.
    constexpr int Gap = 6;
    constexpr qint64 PauseMs = 1500;
    constexpr qint64 StepMs = 150;
    const int cycle = int(chars.size()) + Gap;
    const qint64 elapsed = m_marqueeStart.msecsTo(QDateTime::currentDateTimeUtc());
    const qint64 period = PauseMs + cycle * StepMs;
    const qint64 into = elapsed % period;
    const int offset = into < PauseMs ? 0 : int((into - PauseMs) / StepMs) % cycle;
    QList<uint> strip = chars;
    for (int i = 0; i < Gap; ++i) strip << ' ';
    strip += chars;
    return QString::fromUcs4(reinterpret_cast<const char32_t *>(strip.constData()) + offset, width);
}

Element Tofm::renderBar()
{
    if (!m_player->hasQueue()) return vbox({text(" Nothing playing") | dim, text("")});
    const QVariantMap item = m_player->current();
    const QString etag = item.value("etag").toString();
    const bool favorite = !etag.isEmpty() && m_core->favoriteTracks().contains(etag);
    const QString repeat = m_player->repeatMode() == Player::RepeatOne ? QStringLiteral("↻1")
        : m_player->repeatMode() == Player::RepeatAll ? QStringLiteral("↻ ") : QString();
    const QString subtitle = m_player->displaySubtitle();

    // The title gets what's left of the line after the state, the artist and the marks.
    const int width = m_screen.dimx() - 4 - columns(subtitle) - 3 - 6;
    const QString title = marquee(m_player->displayTitle(), std::max(12, width));

    Element line1 = hbox({
        text(m_player->playing() ? " ▶ " : " ⏸ ") | color(Color::Green),
        text(str(title)) | bold,
        text(subtitle.isEmpty() ? "" : "  " + str(subtitle)) | dim,
        filler(),
        text(favorite ? "♥ " : "  ") | color(Color::Red),
        text(str(repeat) + " ") | color(Color::Cyan),
    });
    Element line2;
    if (m_player->live()) {
        line2 = hbox({text(" ● LIVE") | color(Color::Red) | bold, filler()});
    } else {
        const double duration = m_player->duration();
        const double position = m_player->position();
        line2 = hbox({
            text(" " + str(clock(qint64(position))) + " "),
            gauge(duration > 0 ? float(position / duration) : 0.f) | flex | color(Color::Cyan),
            text(" " + str(clock(qint64(duration))) + " "),
        });
    }
    return vbox({line1, line2});
}

Element Tofm::renderNowPlaying(int height)
{
    if (!m_player->hasQueue()) {
        return vbox({text(" Now Playing") | bold, text(""), text(" Nothing playing. Pick something from the sidebar.") | dim});
    }
    const QVariantMap item = m_player->current();
    const QVariantList items = m_player->items();
    // The cover beside what's playing, the queue under both.
    const int coverRows = std::clamp(height / 2, 4, 14);
    const int coverCols = m_cover->columnsFor(coverRows);
    const int infoWidth = m_screen.dimx() - 20 - coverCols - 4;
    Element info = vbox({
        text(" Now Playing") | dim,
        text(""),
        text(" " + str(marquee(m_player->displayTitle(), infoWidth))) | bold,
        text(" " + str(m_player->displaySubtitle())),
        text(" " + str(m_player->live() ? QString() : item.value("album").toString())) | dim,
        filler(),
    });
    Elements lines = {
        hbox({text(" "), m_cover->render(item.value("imageUri").toString(), coverRows), text(" "), info | flex}),
        text(""),
        hbox({text(" Queue") | bold, text(QStringLiteral("  %1 of %2 · %3")
                                              .arg(m_player->index() + 1).arg(items.size()).arg(m_player->queueTitle()).toStdString()) | dim}),
    };
    // The cover's rows count once (they're one element).
    const int used = coverRows + 2;
    const int rows = std::max(1, height - used);
    m_queueSelected = std::clamp(m_queueSelected, 0, std::max(0, int(items.size()) - 1));
    if (m_queueSelected < m_queueScroll) m_queueScroll = m_queueSelected;
    if (m_queueSelected >= m_queueScroll + rows) m_queueScroll = m_queueSelected - rows + 1;
    m_queueScroll = std::clamp(m_queueScroll, 0, std::max(0, int(items.size()) - rows));
    for (int i = m_queueScroll; i < std::min(int(items.size()), m_queueScroll + rows); ++i) {
        const QVariantMap entry = items[i].toMap();
        const bool playing = i == m_player->index();
        Element line = hbox({
            text(playing ? " ▶ " : "   ") | color(Color::Green) | size(WIDTH, EQUAL, 3),
            text(str(entry.value("title").toString())) | (playing ? bold : nothing) | flex_shrink,
            text("  " + str(entry.value("artist").toString())) | dim | flex_shrink,
            filler(),
        });
        if (i == m_queueSelected) line = line | inverted;
        lines.push_back(line);
    }
    return vbox(lines);
}

bool Tofm::onPlayerKey(const Event &event)
{
    if (event == Event::Character(' ')) m_player->togglePlay();
    else if (event == Event::Character('>')) m_player->next();
    else if (event == Event::Character('<')) m_player->previous();
    else if (event == Event::Character('[')) m_player->seek(m_player->position() - 10);
    else if (event == Event::Character(']')) m_player->seek(m_player->position() + 30);
    else if (event == Event::Character('r')) m_player->cycleRepeat();
    else if (event == Event::Character('p')) {
        m_nowPlaying = !m_nowPlaying;
        m_queueSelected = std::max(0, m_player->index());
    } else {
        return false;
    }
    redraw();
    return true;
}

bool Tofm::onNowPlayingEvent(const Event &event)
{
    const int count = m_player->items().size();
    if (event == Event::ArrowDown || event == Event::Character('j')) ++m_queueSelected;
    else if (event == Event::ArrowUp || event == Event::Character('k')) --m_queueSelected;
    else if (event == Event::PageDown) m_queueSelected += pageHeight() - 1;
    else if (event == Event::PageUp) m_queueSelected -= pageHeight() - 1;
    else if (event == Event::Home || event == Event::Character('g')) m_queueSelected = 0;
    else if (event == Event::End || event == Event::Character('G')) m_queueSelected = count - 1;
    else if (wheel(event)) m_queueSelected += 3 * wheel(event);
    else if (event == Event::Return) m_player->skipTo(m_queueSelected);
    else if (event == Event::Character('f')) toggleFavorite(m_player->current().value("etag").toString());
    else if (event == Event::Escape || event == Event::ArrowLeft || event == Event::Character('h')) m_nowPlaying = false;
    else return true;
    m_queueSelected = std::clamp(m_queueSelected, 0, std::max(0, count - 1));
    redraw();
    return true;
}
