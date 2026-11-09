cmake_minimum_required(VERSION 3.25)
project(takeoutfm VERSION 0.1 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(Qt6 6.6 REQUIRED COMPONENTS Core Gui Widgets Network DBus OpenGL Qml Quick QuickControls2)
find_package(KF6Kirigami REQUIRED)
find_package(KF6I18n 6.8 REQUIRED)
find_package(KF6CoreAddons REQUIRED)
find_package(KF6DBusAddons REQUIRED)
find_package(KF6Crash REQUIRED)
find_package(KF6ColorScheme REQUIRED)
find_package(ftxui 5 REQUIRED)
find_package(PkgConfig REQUIRED)
pkg_check_modules(SECRET REQUIRED IMPORTED_TARGET libsecret-1)
pkg_check_modules(MPV REQUIRED IMPORTED_TARGET mpv)
pkg_check_modules(CHAFA REQUIRED IMPORTED_TARGET chafa)
qt_standard_project_setup(REQUIRES 6.6)
include(GNUInstallDirs)

# ---- The shared Kotlin core, libtakeout.so, built by Gradle ----

set(REPO_DIR "${CMAKE_CURRENT_SOURCE_DIR}/..")
set(TAKEOUT_GRADLE_LIB "${REPO_DIR}/shared/build/bin/linuxX64/takeoutReleaseShared/libtakeout.so")
set(TAKEOUT_LIB "${CMAKE_CURRENT_BINARY_DIR}/libtakeout.so")
file(GLOB_RECURSE TAKEOUT_SOURCES CONFIGURE_DEPENDS
    "${REPO_DIR}/shared/src/commonMain/*" "${REPO_DIR}/shared/src/nativeMain/*"
    "${REPO_DIR}/shared/src/linuxMain/*")
add_custom_command(
    OUTPUT "${TAKEOUT_LIB}"
    COMMAND "${REPO_DIR}/gradlew" -q :shared:linkReleaseSharedLinuxX64
    COMMAND ${CMAKE_COMMAND} -E copy "${TAKEOUT_GRADLE_LIB}" "${TAKEOUT_LIB}"
    WORKING_DIRECTORY "${REPO_DIR}"
    DEPENDS ${TAKEOUT_SOURCES} "${REPO_DIR}/shared/build.gradle.kts" "${REPO_DIR}/shared/src/linuxMain/takeout.map"
    COMMENT "Building libtakeout.so with Gradle"
    USES_TERMINAL)
add_custom_target(takeout_core DEPENDS "${TAKEOUT_LIB}")
add_library(takeout SHARED IMPORTED)
set_target_properties(takeout PROPERTIES
    IMPORTED_LOCATION "${TAKEOUT_LIB}"
    IMPORTED_NO_SONAME ON
    INTERFACE_INCLUDE_DIRECTORIES "${CMAKE_CURRENT_SOURCE_DIR}/src")
add_dependencies(takeout takeout_core)

# ---- The app ----

# Qt Core only, for both the KDE app and tofm: the shared core, mpv playback, MPRIS, secrets.
qt_add_library(takeoutcore STATIC
    src/core/TakeoutCore.cpp src/core/TakeoutCore.h
    src/core/Player.cpp src/core/Player.h
    src/core/Mpris.cpp src/core/Mpris.h
    src/core/SecretStore.cpp src/core/SecretStore.h
    src/core/HttpHost.cpp src/core/HttpHost.h)
target_include_directories(takeoutcore PUBLIC src/core)
target_link_libraries(takeoutcore PUBLIC Qt6::Core Qt6::DBus PkgConfig::MPV takeout PRIVATE Qt6::Network PkgConfig::SECRET)

qt_add_executable(takeoutfm
    src/main.cpp
    src/Core.cpp src/Core.h
    src/ImageCache.cpp src/ImageCache.h
    src/VideoView.cpp src/VideoView.h)
qt_add_qml_module(takeoutfm
    URI TakeoutFM
    QML_FILES
        qml/Main.qml
        qml/SignInPage.qml
        qml/ArtistsPage.qml
        qml/ArtistPage.qml
        qml/AlbumPage.qml
        qml/PlaylistsPage.qml
        qml/TracksPage.qml
        qml/RadioPage.qml
        qml/SearchPage.qml
        qml/SettingsPage.qml
        qml/AlbumTile.qml
        qml/AlbumFlow.qml
        qml/StationTile.qml
        qml/TrackRow.qml
        qml/ArtistRow.qml
        qml/LoadState.qml
        qml/SectionHeading.qml
        qml/MiniPlayer.qml
        qml/NowPlayingPage.qml
        qml/RecentPage.qml
        qml/PodcastsPage.qml
        qml/PodcastPage.qml
        qml/VideoGridPage.qml
        qml/VideoPage.qml
        qml/PersonPage.qml
        qml/VideoTile.qml
        qml/VideoFlow.qml
        qml/EpisodeRow.qml
        qml/PersonTile.qml
        qml/VideoPlayer.qml
        qml/DownloadsPage.qml
        qml/PodcastEpisodePage.qml
        qml/AlbumGridPage.qml
        qml/FavoritesPage.qml
        qml/WatchPage.qml
        qml/CardRow.qml
        qml/ArtistCard.qml
        qml/AppPage.qml
        qml/Marquee.qml
        qml/AddToPlaylistDialog.qml)
target_compile_definitions(takeoutfm PRIVATE PROJECT_VERSION="${PROJECT_VERSION}")
target_link_libraries(takeoutfm PRIVATE
    Qt6::Core Qt6::Gui Qt6::Widgets Qt6::Network Qt6::DBus Qt6::OpenGL Qt6::Qml Qt6::Quick Qt6::QuickControls2
    KF6::Kirigami KF6::I18n KF6::I18nQml KF6::CoreAddons KF6::DBusAddons KF6::Crash KF6::ColorScheme PkgConfig::MPV takeoutcore)
# The window icon when the installed one isn't there (running from the build directory).
qt_add_resources(takeoutfm "icon" PREFIX "/" BASE data
    FILES data/icons/hicolor/256x256/apps/com.takeoutfm.linux.png)
# libtakeout.so sits beside the binary in the build, and in lib/takeoutfm when installed.
set_target_properties(takeoutfm PROPERTIES
    BUILD_RPATH "\$ORIGIN"
    INSTALL_RPATH "\$ORIGIN/../${CMAKE_INSTALL_LIBDIR}/takeout")

# ---- tofm, the terminal client (PLAN.md §13) ----

# Its screens as a library, so the tests can render them.
add_library(tofmui STATIC tui/Tofm.cpp tui/Tofm.h tui/Browse.cpp tui/NowPlaying.cpp tui/Text.h
    tui/Cover.cpp tui/Cover.h tui/CoverCells.cpp tui/CoverCells.h tui/Library.cpp tui/Video.cpp tui/Settings.cpp
    tui/Downloads.cpp tui/Bus.h tui/Requests.h)
target_include_directories(tofmui PUBLIC tui)
target_compile_definitions(tofmui PUBLIC PROJECT_VERSION="${PROJECT_VERSION}")
target_link_libraries(tofmui PUBLIC takeoutcore Qt6::DBus Qt6::Network Qt6::Gui PkgConfig::CHAFA
    ftxui::component ftxui::dom ftxui::screen)
add_executable(tofm tui/main.cpp)
target_link_libraries(tofm PRIVATE tofmui)
set_target_properties(tofm PROPERTIES
    BUILD_RPATH "\$ORIGIN"
    INSTALL_RPATH "\$ORIGIN/../${CMAKE_INSTALL_LIBDIR}/takeout")

# ---- Installing and packaging ----

# Three packages: libtakeout (the shared core, /usr/lib/takeout/libtakeout.so), and takeoutfm
# (the KDE app) and tofm (the terminal client), which both depend on it.
install(FILES "${TAKEOUT_LIB}" DESTINATION ${CMAKE_INSTALL_LIBDIR}/takeout COMPONENT libtakeout)
# Installed as a file, which install/strip and CPACK_STRIP_FILES leave alone: stripped here
# when they strip the rest.
install(CODE "
    if(CMAKE_INSTALL_DO_STRIP)
        execute_process(COMMAND \"${CMAKE_STRIP}\" \"\$ENV{DESTDIR}\${CMAKE_INSTALL_PREFIX}/${CMAKE_INSTALL_LIBDIR}/takeout/libtakeout.so\")
    endif()" COMPONENT libtakeout)
install(FILES data/copyright DESTINATION ${CMAKE_INSTALL_DATADIR}/doc/libtakeout COMPONENT libtakeout)

install(TARGETS takeoutfm RUNTIME DESTINATION ${CMAKE_INSTALL_BINDIR} COMPONENT takeoutfm)
install(FILES data/com.takeoutfm.linux.desktop DESTINATION ${CMAKE_INSTALL_DATADIR}/applications COMPONENT takeoutfm)
install(FILES data/com.takeoutfm.linux.metainfo.xml DESTINATION ${CMAKE_INSTALL_DATADIR}/metainfo COMPONENT takeoutfm)
install(DIRECTORY data/icons/ DESTINATION ${CMAKE_INSTALL_DATADIR}/icons COMPONENT takeoutfm)
install(FILES data/copyright DESTINATION ${CMAKE_INSTALL_DATADIR}/doc/takeoutfm COMPONENT takeoutfm)

install(TARGETS tofm RUNTIME DESTINATION ${CMAKE_INSTALL_BINDIR} COMPONENT tofm)
install(FILES tui/tofm.1 DESTINATION ${CMAKE_INSTALL_MANDIR}/man1 COMPONENT tofm)
install(FILES data/copyright DESTINATION ${CMAKE_INSTALL_DATADIR}/doc/tofm COMPONENT tofm)

# cpack in the build directory makes libtakeout_, takeoutfm_ and tofm_<version>_amd64.deb.
set(CPACK_PACKAGE_NAME takeoutfm)
set(CPACK_PACKAGE_VENDOR TakeoutFM)
set(CPACK_PACKAGE_CONTACT "TakeoutFM <defsub@defsub.com>")
set(CPACK_PACKAGE_HOMEPAGE_URL https://takeout.fm)
set(CPACK_RESOURCE_FILE_LICENSE "${CMAKE_CURRENT_SOURCE_DIR}/../COPYING")
set(CPACK_PACKAGING_INSTALL_PREFIX /usr)
set(CPACK_GENERATOR DEB)
set(CPACK_DEBIAN_FILE_NAME DEB-DEFAULT)
set(CPACK_DEBIAN_PACKAGE_SECTION sound)
# Without debug info (most of each binary's size); the build directory keeps it.
set(CPACK_STRIP_FILES ON)
# Libraries from what each binary links; libtakeout.so (found here) is the libtakeout package's,
# which the apps depend on by name below.
set(CPACK_DEBIAN_PACKAGE_SHLIBDEPS ON)
set(CPACK_DEBIAN_PACKAGE_SHLIBDEPS_PRIVATE_DIRS "${CMAKE_CURRENT_BINARY_DIR}")
# QML modules and the style, which nothing links.
set(CPACK_DEB_COMPONENT_INSTALL ON)
set(CPACK_COMPONENTS_ALL libtakeout takeoutfm tofm)
set(CPACK_DEBIAN_TAKEOUTFM_PACKAGE_NAME takeoutfm)
set(CPACK_DEBIAN_TOFM_PACKAGE_NAME tofm)
set(CPACK_DEBIAN_LIBTAKEOUT_PACKAGE_NAME libtakeout)
set(CPACK_DEBIAN_LIBTAKEOUT_PACKAGE_SECTION libs)
# The C API changes with the apps: each needs the core built with it.
set(CPACK_DEBIAN_TOFM_PACKAGE_DEPENDS "libtakeout (= ${PROJECT_VERSION})")
# Each package's first line is its summary (no CPACK_PACKAGE_DESCRIPTION_SUMMARY: CPackDeb would put it
# in front of all of them).
set(CPACK_DEBIAN_TAKEOUTFM_DESCRIPTION "Music, radio, podcasts, movies and TV from your Takeout server
TakeoutFM for KDE Plasma: browse and play a Takeout media server, with downloads for offline play.")
set(CPACK_DEBIAN_LIBTAKEOUT_DESCRIPTION "TakeoutFM's shared core for takeoutfm and tofm
The Takeout server's API, the library index, downloads, listens and progress, shared by the
TakeoutFM apps (Kotlin Multiplatform, through a small C API).")
set(CPACK_DEBIAN_TOFM_DESCRIPTION "TakeoutFM in the terminal
Music, radio and podcasts from your Takeout server in the terminal, covers drawn with block
characters; movies and TV open in mpv.")
# tofm opens videos in mpv.
set(CPACK_DEBIAN_TOFM_PACKAGE_RECOMMENDS mpv)
set(CPACK_DEBIAN_TAKEOUTFM_PACKAGE_DEPENDS
    "libtakeout (= ${PROJECT_VERSION}), qml6-module-org-kde-kirigami, qml6-module-qtquick-effects, qml6-module-org-kde-ki18n, qml6-module-org-kde-kirigamiaddons-formcard, qml6-module-org-kde-kirigamiaddons-components, qml6-module-org-kde-coreaddons, qml6-module-qtquick-controls, qml6-module-qtquick-layouts, qml6-module-qtquick-window, qml6-module-qtquick-templates, qml6-module-qtqml-workerscript, qml6-module-qtcore, qml6-module-qt-labs-platform | qml6-module-qtquick-dialogs, qml6-module-org-kde-desktop | kf6-qqc2-desktop-style, qt6-image-formats-plugins")
include(CPack)

# ---- Tests ----

enable_testing()
add_executable(bridge_smoke tests/bridge_smoke.cpp)
target_link_libraries(bridge_smoke PRIVATE takeout)
set_target_properties(bridge_smoke PROPERTIES BUILD_RPATH "\$ORIGIN")
add_test(NAME bridge_smoke COMMAND bridge_smoke)
# tofm's screens rendered off-screen and compared as text, and its plain helpers.
add_executable(tofm_test tests/tofm_test.cpp)
target_link_libraries(tofm_test PRIVATE tofmui)
set_target_properties(tofm_test PROPERTIES BUILD_RPATH "\$ORIGIN")
add_test(NAME tofm_test COMMAND tofm_test)
# HttpHost (the core's HTTP through Qt) against a local server.
add_executable(http_host_test tests/http_host_test.cpp)
target_link_libraries(http_host_test PRIVATE takeoutcore Qt6::Network)
set_target_properties(http_host_test PROPERTIES BUILD_RPATH "\$ORIGIN")
add_test(NAME http_host_test COMMAND http_host_test)
