#include "Mpris.h"

#include "Player.h"

#include <QCoreApplication>
#include <QDBusConnection>
#include <QDBusMessage>

namespace {
const QString ObjectPath = QStringLiteral("/org/mpris/MediaPlayer2");
const QString ServiceName = QStringLiteral("org.mpris.MediaPlayer2.takeoutfm");

qlonglong micros(double seconds) { return qlonglong(seconds * 1e6); }

QDBusObjectPath trackId(int index)
{
    return QDBusObjectPath(QStringLiteral("/com/takeoutfm/track/%1").arg(std::max(index, 0)));
}
}

Mpris::Mpris(Player *player, QObject *parent)
    : QObject(parent)
    , m_player(player)
{
    new MprisRoot(this);
    auto *adaptor = new MprisPlayer(this);
    QDBusConnection bus = QDBusConnection::sessionBus();
    bus.registerObject(ObjectPath, this);
    if (!bus.registerService(ServiceName)) qWarning() << "MPRIS: can't take" << ServiceName;

    connect(player, &Player::currentChanged, this, [this] {
        changed({{"Metadata", metadata()}, {"CanGoNext", m_player->index() + 1 < m_player->items().size()}});
    });
    connect(player, &Player::durationChanged, this, [this] {
        changed({{"Metadata", metadata()}, {"CanSeek", !m_player->live() && m_player->duration() > 0}});
    });
    connect(player, &Player::itemsChanged, this, [this] {
        const bool any = m_player->hasQueue();
        changed({{"CanPlay", any}, {"CanPause", any}, {"CanGoPrevious", any},
                 {"CanGoNext", m_player->index() + 1 < m_player->items().size()},
                 {"PlaybackStatus", playbackStatus()}});
    });
    connect(player, &Player::playingChanged, this, [this] { changed({{"PlaybackStatus", playbackStatus()}}); });
    connect(player, &Player::repeatModeChanged, this, [this, adaptor] {
        changed({{"LoopStatus", adaptor->loopStatus()}});
    });
    connect(player, &Player::seeked, adaptor, [this, adaptor] {
        Q_EMIT adaptor->Seeked(micros(m_player->position()));
    });
}

Mpris::~Mpris()
{
    QDBusConnection::sessionBus().unregisterService(ServiceName);
}

QString Mpris::playbackStatus() const
{
    if (!m_player->hasQueue()) return QStringLiteral("Stopped");
    return m_player->playing() ? QStringLiteral("Playing") : QStringLiteral("Paused");
}

QVariantMap Mpris::metadata() const
{
    const QVariantMap item = m_player->current();
    if (item.isEmpty()) return {};
    // A stream's song is "Artist - Title"; the station goes where the album would.
    const auto song = Player::splitStreamTitle(m_player->streamTitle());
    QVariantMap data{
        {"mpris:trackid", QVariant::fromValue(trackId(m_player->index()))},
        {"xesam:title", m_player->displayTitle()},
        {"xesam:artist", QStringList{song ? song->first : item.value("artist").toString()}},
        {"xesam:album", song ? item.value("title").toString() : item.value("album").toString()},
    };
    if (m_player->duration() > 0) data.insert("mpris:length", micros(m_player->duration()));
    const QString art = item.value("imageUri").toString();
    if (!art.isEmpty()) data.insert("mpris:artUrl", art);
    return data;
}

void Mpris::changed(const QVariantMap &properties)
{
    QDBusMessage signal = QDBusMessage::createSignal(
        ObjectPath, QStringLiteral("org.freedesktop.DBus.Properties"), QStringLiteral("PropertiesChanged"));
    signal << QStringLiteral("org.mpris.MediaPlayer2.Player") << properties << QStringList{};
    QDBusConnection::sessionBus().send(signal);
}

MprisRoot::MprisRoot(Mpris *mpris)
    : QDBusAbstractAdaptor(mpris)
    , m_mpris(mpris)
{
}

void MprisRoot::Raise() { Q_EMIT m_mpris->raiseRequested(); }
void MprisRoot::Quit() { QCoreApplication::quit(); }

MprisPlayer::MprisPlayer(Mpris *mpris)
    : QDBusAbstractAdaptor(mpris)
    , m_mpris(mpris)
{
}

QString MprisPlayer::loopStatus() const
{
    switch (m_mpris->player()->repeatMode()) {
    case Player::RepeatAll: return QStringLiteral("Playlist");
    case Player::RepeatOne: return QStringLiteral("Track");
    default: return QStringLiteral("None");
    }
}

void MprisPlayer::setLoopStatus(const QString &status)
{
    m_mpris->player()->setRepeatMode(status == "Playlist" ? Player::RepeatAll
                                     : status == "Track"  ? Player::RepeatOne
                                                          : Player::RepeatOff);
}

qlonglong MprisPlayer::position() const { return micros(m_mpris->player()->position()); }

bool MprisPlayer::canGoNext() const
{
    const Player *p = m_mpris->player();
    return p->index() + 1 < p->items().size();
}

bool MprisPlayer::hasQueue() const { return m_mpris->player()->hasQueue(); }

bool MprisPlayer::canSeek() const
{
    const Player *p = m_mpris->player();
    return !p->live() && p->duration() > 0;
}

void MprisPlayer::Next() { m_mpris->player()->next(); }
void MprisPlayer::Previous() { m_mpris->player()->previous(); }
void MprisPlayer::Pause() { m_mpris->player()->pause(); }
void MprisPlayer::PlayPause() { m_mpris->player()->togglePlay(); }
void MprisPlayer::Stop() { m_mpris->player()->stop(); }
void MprisPlayer::Play() { m_mpris->player()->resume(); }

void MprisPlayer::Seek(qlonglong offset)
{
    if (!canSeek()) return;
    Player *p = m_mpris->player();
    const double to = p->position() + offset / 1e6;
    if (to >= p->duration()) p->next();
    else p->seek(std::max(0.0, to));
}

void MprisPlayer::SetPosition(const QDBusObjectPath &track, qlonglong position)
{
    Player *p = m_mpris->player();
    if (!canSeek() || track != trackId(p->index())) return;
    const double to = position / 1e6;
    if (to >= 0 && to <= p->duration()) p->seek(to);
}
