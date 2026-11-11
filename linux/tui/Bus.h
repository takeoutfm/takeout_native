#pragma once

#include <QDBusConnection>
#include <QDBusConnectionInterface>
#include <QDBusInterface>
#include <QDBusReply>
#include <QObject>
#include <functional>

// tofm's own D-Bus service, com.takeoutfm.tofm at /tofm, so `tofm play …` and `tofm --status`
// reach a tofm that's already running (MPRIS can't load a queue).
class TofmBus : public QObject
{
    Q_OBJECT
    Q_CLASSINFO("D-Bus Interface", "com.takeoutfm.tofm")

public:
    static constexpr const char *Service = "com.takeoutfm.tofm";
    static constexpr const char *Path = "/tofm";

    std::function<void(const QString &kind, const QString &words)> onPlay;
    std::function<QString()> onStatus;

    /** Takes the name; false when another tofm has it. */
    bool start()
    {
        QDBusConnection bus = QDBusConnection::sessionBus();
        return bus.isConnected() && bus.registerService(Service)
            && bus.registerObject(Path, this, QDBusConnection::ExportAllSlots);
    }

    /** The running tofm, if there is one. */
    static bool running()
    {
        QDBusConnection bus = QDBusConnection::sessionBus();
        return bus.isConnected() && bus.interface() && bus.interface()->isServiceRegistered(Service);
    }

    static QDBusInterface remote()
    {
        return QDBusInterface(Service, Path, "com.takeoutfm.tofm", QDBusConnection::sessionBus());
    }

public Q_SLOTS:
    /** Kind "artist", "radio", "album", "song", or "" for the best match; the words to look for. */
    void Play(const QString &kind, const QString &words)
    {
        if (onPlay) onPlay(kind, words);
    }
    QString Status() { return onStatus ? onStatus() : QString(); }
};
