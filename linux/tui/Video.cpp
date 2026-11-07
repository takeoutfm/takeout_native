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
#include <QJsonDocument>
#include <QJsonObject>
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
    if (QStandardPaths::findExecutable(QStringLiteral("mpv")).isEmpty())
        return flash(QStringLiteral("Install mpv to watch videos"));
    flash(QStringLiteral("Opening in mpv…"));

    m_core->call("videoDetails", {{"id", id}}, [this, startOver](const QVariant &result, const QVariant &error) {
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
            // mpv reads the config at start; it needn't stay around.
            const QString config = m_video.configFile;
            QTimer::singleShot(5000, m_core, [config] { if (!config.isEmpty()) QFile::remove(config); });

            // Its control socket appears once it's up.
            m_videoConnect.setInterval(250);
            QObject::disconnect(&m_videoConnect, nullptr, nullptr, nullptr);
            QObject::connect(&m_videoConnect, &QTimer::timeout, m_core, [this] {
                if (!m_video.process || m_video.socket) return m_videoConnect.stop();
                auto *socket = new QLocalSocket;
                socket->connectToServer(m_video.socketPath);
                if (!socket->waitForConnected(100)) {
                    delete socket;
                    return;
                }
                m_videoConnect.stop();
                m_video.socket = socket;
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
                              "{\"command\":[\"observe_property\",2,\"duration\"]}\n");
            });
            m_videoConnect.start();
            m_videoSave.setInterval(10000);
            QObject::disconnect(&m_videoSave, nullptr, nullptr, nullptr);
            QObject::connect(&m_videoSave, &QTimer::timeout, m_core, [this] { saveVideoProgress(); });
            m_videoSave.start();
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

void Tofm::videoEvent(const QByteArray &line)
{
    const QJsonObject event = QJsonDocument::fromJson(line).object();
    if (event.value("event").toString() != "property-change") return;
    const QString name = event.value("name").toString();
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
    m_videoConnect.stop();
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
