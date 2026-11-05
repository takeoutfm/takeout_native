#include "Core.h"
#include "ImageCache.h"
#include "Mpris.h"
#include "Player.h"

#include <KAboutData>
#include <KColorSchemeManager>
#include <KCrash>
#include <KDBusService>
#include <KLocalizedQmlContext>
#include <KLocalizedString>
#include <QApplication>
#include <QDir>
#include <QIcon>
#include <QQmlApplicationEngine>
#include <QQuickStyle>
#include <QQuickWindow>
#include <QSGRendererInterface>
#include <QStandardPaths>
#include <clocale>

int main(int argc, char *argv[])
{
    QApplication app(argc, argv);
    KLocalizedString::setApplicationDomain("takeoutfm");

    // Who and what the app is: the About page, the window's app id, crash reports, D-Bus name.
    KAboutData about(QStringLiteral("takeoutfm"), QStringLiteral("TakeoutFM"), QStringLiteral(PROJECT_VERSION),
                     i18n("Music, radio, podcasts, movies and TV from your Takeout server"),
                     KAboutLicense::GPL_V3, i18n("© 2026 defsub"));
    about.setOrganizationDomain("takeoutfm.com");
    about.setDesktopFileName(QStringLiteral("com.takeoutfm.linux"));
    about.setHomepage(QStringLiteral("https://takeout.fm"));
    // Not KDE's tracker (the default): Report a Bug and crash reports go to the project's issues.
    about.setBugAddress(QByteArrayLiteral("https://gitlab.com/takeoutfm/takeout_native/-/issues"));
    about.addAuthor(QStringLiteral("defsub"));
    // GPL-3.0 "or (at your option) any later version".
    about.setLicense(KAboutLicense::GPL_V3, KAboutLicense::OrLaterVersions);
    KAboutData::setApplicationData(about);
    KCrash::initialize();
    // One TakeoutFM at a time: launching it again brings this window forward instead.
    KDBusService service(KDBusService::Unique);

    app.setWindowIcon(QIcon::fromTheme("com.takeoutfm.linux", QIcon(":/icons/hicolor/256x256/apps/com.takeoutfm.linux.png")));
    // libmpv needs C number formatting, which QApplication replaced with the user's locale.
    std::setlocale(LC_NUMERIC, "C");
    // mpv draws video through OpenGL, so Qt Quick must use it too.
    QQuickWindow::setGraphicsApi(QSGRendererInterface::OpenGL);
    // Plasma's look; Kirigami follows its colours elsewhere too.
    if (qEnvironmentVariableIsEmpty("QT_QUICK_CONTROLS_STYLE")) QQuickStyle::setStyle("org.kde.desktop");

    Core core(nullptr);
    const QString dataDir = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation);
    if (!core.start(dataDir, "TakeoutFM-Linux/" PROJECT_VERSION)) {
        qCritical("TakeoutFM: can't open the data in %s", qPrintable(dataDir));
        return 1;
    }

    // Settings → Theme: Breeze Light or Breeze Dark for this app only, or Plasma's colours
    // (System). The shared settings keep the choice, so the manager doesn't save its own.
    auto *schemes = KColorSchemeManager::instance();
    schemes->setAutosaveChanges(false);
    QString appliedTheme;
    const auto applyTheme = [&core, schemes, &appliedTheme] {
        const QString theme = core.settings().value("theme").toString();
        if (theme.isEmpty() || theme == appliedTheme) return; // not known yet, or unchanged
        appliedTheme = theme;
        schemes->activateSchemeId(theme == "Light" ? QStringLiteral("BreezeLight")
                                  : theme == "Dark" ? QStringLiteral("BreezeDark")
                                                    : QString());
    };
    QObject::connect(&core, &Core::settingsChanged, &app, applyTheme);
    applyTheme();

    Player player(&core);
    Mpris mpris(&player);

    QQmlApplicationEngine engine;
    // KDE's translations in QML: KI18n.i18n() (org.kde.ki18n) and the context functions
    // Kirigami Addons calls.
    KLocalization::setupLocalizedContext(&engine);
    ImageCache images;
    engine.setNetworkAccessManagerFactory(&images);
    QObject::connect(&engine, &QQmlApplicationEngine::objectCreationFailed, &app, [] { QCoreApplication::exit(1); },
                     Qt::QueuedConnection);
    engine.loadFromModule("TakeoutFM", "Main");
    const auto raise = [&engine] {
        if (engine.rootObjects().isEmpty()) return;
        if (auto *window = qobject_cast<QQuickWindow *>(engine.rootObjects().first())) {
            window->show();
            window->raise();
            window->requestActivate();
        }
    };
    QObject::connect(&mpris, &Mpris::raiseRequested, &engine, raise);
    QObject::connect(&service, &KDBusService::activateRequested, &engine, raise);
    return app.exec();
}
