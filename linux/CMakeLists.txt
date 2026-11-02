cmake_minimum_required(VERSION 3.25)
project(takeoutfm VERSION 0.1 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(Qt6 6.6 REQUIRED COMPONENTS Core Gui Widgets Network DBus OpenGL Qml Quick QuickControls2)
find_package(KF6Kirigami REQUIRED)
find_package(PkgConfig REQUIRED)
pkg_check_modules(SECRET REQUIRED IMPORTED_TARGET libsecret-1)
pkg_check_modules(MPV REQUIRED IMPORTED_TARGET mpv)
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
    DEPENDS ${TAKEOUT_SOURCES} "${REPO_DIR}/shared/build.gradle.kts"
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

qt_add_executable(takeoutfm
    src/main.cpp
    src/Core.cpp src/Core.h
    src/SecretStore.cpp src/SecretStore.h
    src/ImageCache.cpp src/ImageCache.h
    src/Player.cpp src/Player.h
    src/Mpris.cpp src/Mpris.h
    src/VideoView.cpp src/VideoView.h)
qt_add_qml_module(takeoutfm
    URI TakeoutFM
    QML_FILES
        qml/Main.qml
        qml/SignInPage.qml
        qml/HomePage.qml
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
        qml/MoviesPage.qml
        qml/ShowsPage.qml
        qml/VideoGridPage.qml
        qml/VideoPage.qml
        qml/PersonPage.qml
        qml/VideoTile.qml
        qml/VideoFlow.qml
        qml/EpisodeRow.qml
        qml/PersonTile.qml
        qml/VideoPlayer.qml
        qml/DownloadsPage.qml
        qml/PodcastEpisodePage.qml)
target_compile_definitions(takeoutfm PRIVATE PROJECT_VERSION="${PROJECT_VERSION}")
target_link_libraries(takeoutfm PRIVATE
    Qt6::Core Qt6::Gui Qt6::Widgets Qt6::Network Qt6::DBus Qt6::OpenGL Qt6::Qml Qt6::Quick Qt6::QuickControls2
    KF6::Kirigami PkgConfig::SECRET PkgConfig::MPV takeout)
# The window icon when the installed one isn't there (running from the build directory).
qt_add_resources(takeoutfm "icon" PREFIX "/" BASE data
    FILES data/icons/hicolor/256x256/apps/com.takeoutfm.linux.png)
# libtakeout.so sits beside the binary in the build, and in lib/takeoutfm when installed.
set_target_properties(takeoutfm PROPERTIES
    BUILD_RPATH "\$ORIGIN"
    INSTALL_RPATH "\$ORIGIN/../${CMAKE_INSTALL_LIBDIR}/takeoutfm")

# ---- Installing and packaging ----

install(TARGETS takeoutfm RUNTIME DESTINATION ${CMAKE_INSTALL_BINDIR})
install(FILES "${TAKEOUT_LIB}" DESTINATION ${CMAKE_INSTALL_LIBDIR}/takeoutfm)
install(FILES data/com.takeoutfm.linux.desktop DESTINATION ${CMAKE_INSTALL_DATADIR}/applications)
install(FILES data/com.takeoutfm.linux.metainfo.xml DESTINATION ${CMAKE_INSTALL_DATADIR}/metainfo)
install(DIRECTORY data/icons/ DESTINATION ${CMAKE_INSTALL_DATADIR}/icons)
install(FILES data/copyright DESTINATION ${CMAKE_INSTALL_DOCDIR})

# cpack -G DEB in the build directory makes takeoutfm_<version>_amd64.deb.
set(CPACK_PACKAGE_NAME takeoutfm)
set(CPACK_PACKAGE_VENDOR TakeoutFM)
set(CPACK_PACKAGE_CONTACT "TakeoutFM <defsub@defsub.com>")
set(CPACK_PACKAGE_DESCRIPTION_SUMMARY "Music, radio, podcasts, movies and TV from your Takeout server")
set(CPACK_PACKAGE_HOMEPAGE_URL https://takeout.fm)
set(CPACK_RESOURCE_FILE_LICENSE "${CMAKE_CURRENT_SOURCE_DIR}/../COPYING")
set(CPACK_PACKAGING_INSTALL_PREFIX /usr)
set(CPACK_GENERATOR DEB)
set(CPACK_DEBIAN_FILE_NAME DEB-DEFAULT)
set(CPACK_DEBIAN_PACKAGE_SECTION sound)
# Libraries from what the binary links; libtakeout.so is the package's own.
set(CPACK_DEBIAN_PACKAGE_SHLIBDEPS ON)
set(CPACK_DEBIAN_PACKAGE_SHLIBDEPS_PRIVATE_DIRS "${CMAKE_CURRENT_BINARY_DIR}")
# QML modules and the style, which nothing links.
set(CPACK_DEBIAN_PACKAGE_DEPENDS
    "qml6-module-org-kde-kirigami, qml6-module-qtquick-controls, qml6-module-qtquick-layouts, qml6-module-qtquick-window, qml6-module-qtquick-templates, qml6-module-qtqml-workerscript, qml6-module-qtcore, qml6-module-qt-labs-platform | qml6-module-qtquick-dialogs, qml6-module-org-kde-desktop | kf6-qqc2-desktop-style, qt6-image-formats-plugins")
include(CPack)

# ---- Tests ----

enable_testing()
add_executable(bridge_smoke tests/bridge_smoke.cpp)
target_link_libraries(bridge_smoke PRIVATE takeout)
set_target_properties(bridge_smoke PROPERTIES BUILD_RPATH "\$ORIGIN")
add_test(NAME bridge_smoke COMMAND bridge_smoke)
