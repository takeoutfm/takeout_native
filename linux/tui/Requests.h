#pragma once

#include <QStringList>
#include <optional>

// `tofm play …`: what to look for. The running tofm searches (GET, as the Search page) and
// plays the match the usual way (artist mixes, albums, tracks, stations), so nothing is
// written to the server — no PATCH of the user's current playlist.

struct PlayRequest {
    /** "artist", "radio", "album", "song", or "" for the best match of the words. */
    QString kind;
    QString words;
};

namespace requests {
/** The words after `tofm play`: "artist Beck", "album Odelay", "song Loser", "radio Beck", or words. */
inline std::optional<PlayRequest> parse(const QStringList &args)
{
    if (args.isEmpty()) return std::nullopt;
    const QString kind = args.first().toLower();
    const QString rest = args.mid(1).join(' ').trimmed();
    for (const char *known : {"artist", "radio", "album", "song"}) {
        if (kind == known && !rest.isEmpty()) return PlayRequest{kind, rest};
    }
    if (kind == "track" && !rest.isEmpty()) return PlayRequest{QStringLiteral("song"), rest};
    const QString words = args.join(' ').trimmed();
    if (words.isEmpty()) return std::nullopt;
    return PlayRequest{QString(), words};
}
}
