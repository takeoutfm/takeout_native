// tofm: movies and episodes play in mpv, a program of its own (tofm draws text; video needs a
// window). The media token never goes on mpv's command line, where `ps` shows it to every user:
// it's in a config file only the user can read, passed with --include and deleted once mpv has
// read it. mpv's JSON IPC reports the position, saved as watch progress as in the other apps.

#include "Tofm.h"

#include "Player.h"
#include "TakeoutCore.h"
#include "Text.h"

#include <QCoreApplication>
#include <QFile>
#include <QFileInfo>
#include <QJsonDocument>
#include <QJsonObject>
#include <QFileSystemWatcher>
#include <QLocalSocket>
#include <QProcess>
#include <QStandardPaths>
#include <QTemporaryFile>

namespace {
/** Where the token file and the socket go: $XDG_RUNTIME_DIR (private, in memory) when there is one. */
QString runtimeDir()
{
    const QString dir = QStandardPaths::writableLocation(QStandardPaths::RuntimeLocation);
    return dir.isEmpty() ? QStandardPaths::writableLocation(QStandardPaths::TempLocation) : dir;
}
}

void Tofm::playVideo(const QString &id, bool startOver)
{
    if (m_video.process) return flash(QStringLiteral("A video is already open in mpv"));
    if (qEnvironmentVariableIsEmpty("DISPLAY") && qEnvironmentVariableIsEmpty("WAYLAND_DISPLAY"))
        return flash(QStringLiteral("Videos need a display (tofm opens them in mpv)"));
    // Settings → Video player: mpv unless the user named another command.
    const QStringList command = QProcess::splitCommand(m_prefs.value("videoPlayer").toString().trimmed());
    const QString program = command.isEmpty() ? QStringLiteral("mpv") : command.first();
    const bool mpv = QFileInfo(program).fileName() == "mpv";
    if (QStandardPaths::findExecutable(program).isEmpty() && !QFileInfo(program).isExecutable())
        return flash(mpv ? QStringLiteral("Install mpv to watch videos") : QStringLiteral("Can't find ") + program);
    flash(QStringLiteral("Opening in mpv…"));

    m_core->call("videoDetails", {{"id", id}}, [this, startOver, mpv, command](const QVariant &result, const QVariant &error) {
        const QVariantMap d = result.toMap();
        const QString uri = d.value("videoUri").toString();
        if (error.isValid() || uri.isEmpty()) return flash(QStringLiteral("Nothing to play"));
        const QString etag = d.value("etag").toString();
        const QVariantMap episode = d.value("episode").toMap();
        const QString title = episode.isEmpty()
            ? d.value("title").toString()
            : QStringLiteral("%1 · S%2E%3 %4").arg(episode.value("seriesTitle").toString()).arg(episode.value("season").toInt())
                  .arg(episode.value("episode").toInt()).arg(episode.value("name").toString());
        const QString local = m_core->localFile(etag);

        // Another player can't be given the token (or report where it got): downloads only.
        if (!mpv) {
            if (local.isEmpty())
                return flash(QStringLiteral("%1 can only open downloaded videos: ↓ Download it first").arg(command.first()));
            QStringList args = command.mid(1);
            if (args.contains(QStringLiteral("{file}"))) args.replaceInStrings(QStringLiteral("{file}"), local);
            else args << local;
            auto *process = new QProcess;
            process->setProgram(command.first());
            process->setArguments(args);
            process->setStandardInputFile(QProcess::nullDevice());
            process->setStandardOutputFile(QProcess::nullDevice());
            process->setStandardErrorFile(QProcess::nullDevice());
            QObject::connect(process, &QProcess::finished, m_core, [this] { videoClosed(); });
            m_video = {process, nullptr, title, QString(), QString(), QString(), 0, 0, {}};
            process->start();
            if (!process->waitForStarted(3000)) {
                videoClosed();
                return flash(QStringLiteral("Can't start ") + command.first());
            }
            m_player->pause();
            flash(QStringLiteral("Playing in %1: %2").arg(QFileInfo(command.first()).fileName(), title));
            return redraw();
        }

        auto launch = [this, uri, local, etag, title](const QString &authorization, double seconds) {
            if (m_video.process) return;
            const QString dir = runtimeDir();
            QStringList args = {QStringLiteral("--no-terminal"), QStringLiteral("--force-window=immediate"),
                                QStringLiteral("--force-media-title=") + title};
            // The token, for the signed-in server's stream only (a download needs none).
            if (local.isEmpty() && !authorization.isEmpty() && uri.startsWith(m_core->endpoint() + "/")) {
                QTemporaryFile config(dir + "/tofm-mpv-XXXXXX.conf"); // 0600
                config.setAutoRemove(false);
                if (!config.open()) return flash(QStringLiteral("Can't start mpv"));
                config.write("http-header-fields=Authorization: " + authorization.toUtf8() + "\n");
                config.close();
                m_video.configFile = config.fileName();
                args << QStringLiteral("--include=") + m_video.configFile;
            }
            m_video.socketPath = QStringLiteral("%1/tofm-mpv-%2.sock").arg(dir).arg(QCoreApplication::applicationPid());
            QFile::remove(m_video.socketPath);
            args << QStringLiteral("--input-ipc-server=") + m_video.socketPath;
            if (seconds > 1) args << QStringLiteral("--start=%1").arg(seconds, 0, 'f', 1);
            args << QStringLiteral("--") << (local.isEmpty() ? uri : local);

            auto *process = new QProcess;
            process->setProgram(QStringLiteral("mpv"));
            process->setArguments(args);
            // Nothing of mpv's may draw over tofm's screen or read its keys.
            process->setStandardInputFile(QProcess::nullDevice());
            process->setStandardOutputFile(QProcess::nullDevice());
            process->setStandardErrorFile(QProcess::nullDevice());
            QObject::connect(process, &QProcess::finished, m_core, [this] { videoClosed(); });
            m_video = {process, nullptr, title, etag, m_video.configFile, m_video.socketPath, seconds, 0, {}};
            process->start();
            if (!process->waitForStarted(3000)) {
                videoClosed();
                return flash(QStringLiteral("Can't start mpv"));
            }
            m_player->pause();
            flash(QStringLiteral("Playing in mpv: ") + title);

            // Its control socket appears in the runtime folder once it's up: connect then (the
            // watcher says when the folder changes; no polling).
            delete m_videoWatch;
            m_videoWatch = new QFileSystemWatcher({dir});
            QObject::connect(m_videoWatch, &QFileSystemWatcher::directoryChanged, m_core, [this] { connectVideo(); });
            connectVideo();
            // Saved every 10 s while playing (started when mpv says it plays), and on pause.
            m_videoSave.setInterval(10000);
            QObject::disconnect(&m_videoSave, nullptr, nullptr, nullptr);
            QObject::connect(&m_videoSave, &QTimer::timeout, m_core, [this] { saveVideoProgress(); });
            redraw();
        };
        auto from = [this, local, uri, launch](double seconds) {
            if (!local.isEmpty()) return launch({}, seconds);
            m_core->call("mediaAuthorization", {{"url", uri}},
                         [launch, seconds](const QVariant &auth, const QVariant &) { launch(auth.toString(), seconds); });
        };
        if (startOver || etag.isEmpty()) return from(0);
        m_core->call("resumePositionMs", {{"etag", etag}},
                     [from](const QVariant &ms, const QVariant &) { from(ms.toLongLong() / 1000.0); });
    });
}

void Tofm::connectVideo()
{
    if (!m_video.process || m_video.socket || m_videoConnecting || !QFile::exists(m_video.socketPath)) return;
    m_videoConnecting = true;
    auto *socket = new QLocalSocket;
    QObject::connect(socket, &QLocalSocket::errorOccurred, m_core, [this, socket] {
        // not listening yet: the next change in the folder tries again
        m_videoConnecting = false;
        socket->deleteLater();
    });
    QObject::connect(socket, &QLocalSocket::connected, m_core, [this, socket] {
        m_videoConnecting = false;
        if (!m_video.process) return socket->deleteLater();
        m_video.socket = socket;
        delete m_videoWatch;
        m_videoWatch = nullptr;
        // mpv read its config (the token) before opening this socket: it needn't stay around.
        if (!m_video.configFile.isEmpty()) QFile::remove(m_video.configFile);
        QObject::connect(socket, &QLocalSocket::readyRead, m_core, [this] {
            if (!m_video.socket) return;
            m_video.pending += m_video.socket->readAll();
            qsizetype end;
            while ((end = m_video.pending.indexOf('\n')) >= 0) {
                videoEvent(m_video.pending.left(end));
                m_video.pending.remove(0, end + 1);
            }
        });
        socket->write("{\"command\":[\"observe_property\",1,\"time-pos\"]}\n"
                      "{\"command\":[\"observe_property\",2,\"duration\"]}\n"
                      "{\"command\":[\"observe_property\",3,\"pause\"]}\n");
    });
    socket->connectToServer(m_video.socketPath);
}

void Tofm::videoEvent(const QByteArray &line)
{
    const QJsonObject event = QJsonDocument::fromJson(line).object();
    if (event.value("event").toString() != "property-change") return;
    const QString name = event.value("name").toString();
    if (name == "pause") {
        // saved every 10 s only while it plays; at once on pausing
        if (event.value("data").toBool()) {
            m_videoSave.stop();
            saveVideoProgress();
        } else {
            m_videoSave.start();
        }
        return;
    }
    const double value = event.value("data").toDouble(-1);
    if (value < 0) return;
    if (name == "time-pos") m_video.position = value;
    else if (name == "duration") m_video.duration = value;
}

void Tofm::saveVideoProgress()
{
    if (m_video.etag.isEmpty() || m_video.duration <= 0 || m_video.position <= 0) return;
    m_core->call("reportProgress", {{"etag", m_video.etag}, {"positionMs", qint64(m_video.position * 1000)},
                                    {"durationMs", qint64(m_video.duration * 1000)}});
}

void Tofm::videoClosed()
{
    delete m_videoWatch;
    m_videoWatch = nullptr;
    m_videoConnecting = false;
    m_videoSave.stop();
    saveVideoProgress();
    if (m_video.position > 0 && m_video.duration > 0)
        flash(QStringLiteral("Stopped at %1 of %2").arg(clock(qint64(m_video.position)), clock(qint64(m_video.duration))));
    if (m_video.socket) m_video.socket->deleteLater();
    if (m_video.process) m_video.process->deleteLater();
    if (!m_video.configFile.isEmpty()) QFile::remove(m_video.configFile);
    if (!m_video.socketPath.isEmpty()) QFile::remove(m_video.socketPath);
    m_video = {};
    redraw();
}
