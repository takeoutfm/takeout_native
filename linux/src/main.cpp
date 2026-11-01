#include "Core.h"
#include "ImageCache.h"
#include "Mpris.h"
#include "Player.h"

#include <QApplication>
#include <QDir>
#include <QIcon>
#include <QQmlApplicationEngine>
#include <QQuickStyle>
#include <QQuickWindow>
#include <QStandardPaths>
#include <clocale>

int main(int argc, char *argv[])
{
    QApplication app(argc, argv);
    app.setApplicationName("takeoutfm");
    app.setApplicationDisplayName("TakeoutFM");
    app.setOrganizationDomain("takeoutfm.com");
    app.setApplicationVersion(PROJECT_VERSION);
    app.setDesktopFileName("com.takeoutfm.linux");
    app.setWindowIcon(QIcon::fromTheme("audio-headphones"));
    // libmpv needs C number formatting, which QApplication replaced with the user's locale.
    std::setlocale(LC_NUMERIC, "C");
    // Plasma's look; Kirigami follows its colours elsewhere too.
    if (qEnvironmentVariableIsEmpty("QT_QUICK_CONTROLS_STYLE")) QQuickStyle::setStyle("org.kde.desktop");

    Core core;
    const QString dataDir = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation);
    if (!core.start(dataDir, "TakeoutFM-Linux/" PROJECT_VERSION)) {
        qCritical("TakeoutFM: can't open the data in %s", qPrintable(dataDir));
        return 1;
    }

    Player player(&core);
    Mpris mpris(&player);

    QQmlApplicationEngine engine;
    ImageCache images;
    engine.setNetworkAccessManagerFactory(&images);
    qmlRegisterSingletonInstance("TakeoutFM.Core", 1, 0, "Core", &core);
    qmlRegisterSingletonInstance("TakeoutFM.Core", 1, 0, "Player", &player);
    QObject::connect(&engine, &QQmlApplicationEngine::objectCreationFailed, &app, [] { QCoreApplication::exit(1); },
                     Qt::QueuedConnection);
    engine.loadFromModule("TakeoutFM", "Main");
    QObject::connect(&mpris, &Mpris::raiseRequested, &engine, [&engine] {
        if (engine.rootObjects().isEmpty()) return;
        if (auto *window = qobject_cast<QQuickWindow *>(engine.rootObjects().first())) {
            window->show();
            window->raise();
            window->requestActivate();
        }
    });
    return app.exec();
}
