#include "Mpris.h"
#include "Player.h"
#include "SecretStore.h"
#include "TakeoutCore.h"
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
    "Usage: tofm [--help] [--version]\n"
    "TakeoutFM in the terminal: music, radio and podcasts from your Takeout server.\n"
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
    if (!args.isEmpty()) {
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

    Tofm tofm(&core);
    const int code = tofm.run();
    player.pause();
    return code;
}
