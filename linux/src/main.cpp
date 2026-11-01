#include "Core.h"
#include "ImageCache.h"

#include <QApplication>
#include <QDir>
#include <QIcon>
#include <QQmlApplicationEngine>
#include <QQuickStyle>
#include <QStandardPaths>

int main(int argc, char *argv[])
{
    QApplication app(argc, argv);
    app.setApplicationName("takeoutfm");
    app.setApplicationDisplayName("TakeoutFM");
    app.setOrganizationDomain("takeoutfm.com");
    app.setApplicationVersion(PROJECT_VERSION);
    app.setDesktopFileName("com.takeoutfm.linux");
    app.setWindowIcon(QIcon::fromTheme("audio-headphones"));
    // Plasma's look; Kirigami follows its colours elsewhere too.
    if (qEnvironmentVariableIsEmpty("QT_QUICK_CONTROLS_STYLE")) QQuickStyle::setStyle("org.kde.desktop");

    Core core;
    const QString dataDir = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation);
    if (!core.start(dataDir, "TakeoutFM-Linux/" PROJECT_VERSION)) {
        qCritical("TakeoutFM: can't open the data in %s", qPrintable(dataDir));
        return 1;
    }

    QQmlApplicationEngine engine;
    ImageCache images;
    engine.setNetworkAccessManagerFactory(&images);
    qmlRegisterSingletonInstance("TakeoutFM.Core", 1, 0, "Core", &core);
    QObject::connect(&engine, &QQmlApplicationEngine::objectCreationFailed, &app, [] { QCoreApplication::exit(1); },
                     Qt::QueuedConnection);
    engine.loadFromModule("TakeoutFM", "Main");
    return app.exec();
}
