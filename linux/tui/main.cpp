#include "Bus.h"
#include "Mpris.h"
#include "Player.h"
#include "SecretStore.h"
#include "TakeoutCore.h"
#include "Requests.h"
#include "Tofm.h"

#include <QCoreApplication>
#include <QDir>
#include <QFile>
#include <QStandardPaths>
#include <clocale>
#include <cstdio>
#include <fcntl.h>
#include <unistd.h>

namespace {
const char *const Usage =
    "Usage: tofm [--help] [--version] [--status] [play [artist|album|song|radio] WORDS…]\n"
    "TakeoutFM in the terminal: music, radio and podcasts from your Takeout server.\n"
    "  tofm play artist Beck     the artist's popular tracks (also: album, song, radio)\n"
    "  tofm play odelay          the best match for the words (or the server's syntax: guitar:jack)\n"
    "  tofm --status             what a running tofm is playing (for status bars)\n"
    "A running tofm gets the request; else tofm starts and plays it.\n"
    "Logs: $XDG_STATE_HOME/tofm/tofm.log (~/.local/state/tofm/tofm.log)\n";

/**
 * Qt, mpv and libsecret warnings would draw over the screen: from here on stderr goes to
 * [path] (appended).
 */
void logTo(const QString &path)
{
    const int fd = ::open(QFile::encodeName(path).constData(), O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0600);
    if (fd < 0) return;
    ::dup2(fd, STDERR_FILENO);
    ::close(fd);
}
}

int main(int argc, char *argv[])
{
    QCoreApplication app(argc, argv);
    QCoreApplication::setApplicationName(QStringLiteral("tofm"));
    QCoreApplication::setApplicationVersion(QStringLiteral(PROJECT_VERSION));
    const QStringList args = app.arguments().mid(1);
    if (args.contains("--version")) {
        std::printf("tofm %s\n", PROJECT_VERSION);
        return 0;
    }
    std::optional<PlayRequest> play;
    if (args.value(0) == "--status") {
        // For status bars: what a running tofm plays, else nothing (and a failure).
        if (!TofmBus::running()) return 1;
        auto remote = TofmBus::remote();
        const QDBusReply<QString> reply = remote.call("Status");
        if (!reply.isValid() || reply.value().isEmpty()) return 1;
        std::printf("%s\n", qPrintable(reply.value()));
        return 0;
    } else if (args.value(0) == "play") {
        play = requests::parse(args.mid(1));
        if (!play) {
            std::fputs(Usage, stderr);
            return 2;
        }
        // Handed to a running tofm, which plays it.
        if (TofmBus::running()) {
            auto remote = TofmBus::remote();
            const QDBusMessage reply = remote.call("Play", play->kind, play->words);
            if (reply.type() == QDBusMessage::ErrorMessage) {
                std::fprintf(stderr, "tofm: %s\n", qPrintable(reply.errorMessage()));
                return 1;
            }
            std::printf("Asked the running tofm to play %s\n", qPrintable(play->words));
            return 0;
        }
    } else if (!args.isEmpty()) {
        std::fputs(Usage, args.contains("--help") || args.contains("-h") ? stdout : stderr);
        return args.contains("--help") || args.contains("-h") ? 0 : 2;
    }
    if (!::isatty(STDIN_FILENO) || !::isatty(STDOUT_FILENO)) {
        std::fputs("tofm needs a terminal.\n", stderr);
        return 1;
    }
    // libmpv needs C number formatting.
    std::setlocale(LC_NUMERIC, "C");

    // Its own data, apart from the KDE app's: ~/.local/share/tofm.
    const QString dataDir = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation);
    const QString stateDir = QStandardPaths::writableLocation(QStandardPaths::GenericStateLocation) + "/tofm";
    QDir().mkpath(dataDir);
    QDir().mkpath(stateDir);
    logTo(stateDir + "/tofm.log");

    // No keyring (SSH, headless): the sign-in in a file only the user can read.
    SecretStore::setFallbackFile((dataDir + "/secrets.json").toStdString());
    TakeoutCore core;
    if (!core.start(dataDir, QStringLiteral("TakeoutFM-Terminal/" PROJECT_VERSION), QStringLiteral("tofm"))) {
        std::fprintf(stdout, "tofm: can't open the data in %s\n", qPrintable(dataDir));
        return 1;
    }
    Player player(&core, QStringLiteral("tofm"), QStringLiteral("TakeoutFM-Terminal/" PROJECT_VERSION));
    // playerctl -p tofm; no window to raise.
    Mpris mpris(&player, {QStringLiteral("tofm"), QStringLiteral("tofm"), QString(), false});

    Tofm tofm(&core, &player);
    // `tofm play …` and `tofm --status` from other terminals reach this one.
    TofmBus bus;
    bus.onPlay = [&tofm](const QString &kind, const QString &words) { tofm.playRequest(kind, words); };
    bus.onStatus = [&tofm] { return tofm.status(); };
    bus.start();
    if (play) tofm.playRequest(play->kind, play->words);
    const int code = tofm.run();
    player.pause();
    return code;
}
