#! /usr/bin/env bash
# Extracts the app's strings for translators into $podir/takeoutfm.pot (KDE's i18n tooling:
# the KI18n.i18n*() calls in QML and i18n*() in C++).
$XGETTEXT $(find src qml -name '*.cpp' -o -name '*.h' -o -name '*.qml') -o $podir/takeoutfm.pot
