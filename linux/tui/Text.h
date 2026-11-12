#pragma once

#include <QString>
#include <QStringList>
#include <ftxui/component/event.hpp>
#include <ftxui/component/mouse.hpp>
#include <ftxui/dom/elements.hpp>
#include <string>

// Small helpers for tofm's screens.

inline std::string str(const QString &text) { return text.toStdString(); }

/** Seconds as m:ss, or h:mm:ss from an hour. */
inline QString clock(qint64 seconds)
{
    seconds = std::max<qint64>(seconds, 0);
    const QString ss = QStringLiteral("%1").arg(seconds % 60, 2, 10, QLatin1Char('0'));
    if (seconds >= 3600)
        return QStringLiteral("%1:%2:%3").arg(seconds / 3600).arg((seconds / 60) % 60, 2, 10, QLatin1Char('0')).arg(ss);
    return QStringLiteral("%1:%2").arg(seconds / 60).arg(ss);
}

/**
 * A Recently Played entry's secondary text: who made it (a long list of artists, as Favorite
 * Tracks has, shortened to "A, B + 9 more") and what it holds ("11 tracks", "52 episodes", or
 * "radio" for a stream), joined with " · ".
 */
inline QString recentDetail(const QString &title, const QString &creator, int items, bool podcast, bool station)
{
    QString who = creator == title ? QString() : creator;
    QStringList artists = who.split(QStringLiteral(" • "), Qt::SkipEmptyParts);
    if (artists.size() > 2)
        who = QStringLiteral("%1, %2 + %3 more").arg(artists[0], artists[1]).arg(artists.size() - 2);
    else if (artists.size() == 2)
        who = artists.join(QStringLiteral(", "));
    const QString what = station ? QStringLiteral("radio")
        : podcast ? (items == 1 ? QStringLiteral("1 episode") : QStringLiteral("%1 episodes").arg(items))
                  : (items == 1 ? QStringLiteral("1 track") : QStringLiteral("%1 tracks").arg(items));
    return who.isEmpty() ? what : who + QStringLiteral(" · ") + what;
}

/** "key what" for the hints at the bottom of a screen. */
inline ftxui::Element keyHint(const std::string &key, const std::string &what)
{
    using namespace ftxui;
    return hbox({text(key) | bold, text(" " + what + "  ") | dim});
}

/** The mouse wheel: 1 down, -1 up, else 0 (a copy: FTXUI's Event::mouse() isn't const). */
inline int wheel(ftxui::Event event)
{
    if (!event.is_mouse()) return 0;
    const auto button = event.mouse().button;
    return button == ftxui::Mouse::WheelDown ? 1 : button == ftxui::Mouse::WheelUp ? -1 : 0;
}
