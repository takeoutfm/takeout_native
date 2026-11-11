#pragma once

#include "CoverCells.h"

#include <QHash>
#include <QImage>
#include <QNetworkAccessManager>
#include <QSet>
#include <QString>
#include <ftxui/dom/elements.hpp>
#include <functional>

// Album covers for tofm: fetched once (kept in $XDG_CACHE_HOME/tofm/covers), drawn as coloured
// block characters that are part of FTXUI's screen, so redraws never lose them and any terminal
// (SSH included) shows them. Kitty / sixel images are for later (PLAN.md §13, T4).
class Cover
{
public:
    /** [loaded] is called when a cover arrives (to redraw). */
    explicit Cover(std::function<void()> loaded);

    /**
     * [url]'s cover in [rows] rows, as wide as it needs to look square; a blank box of that size
     * until it's here (or if it can't be had).
     */
    ftxui::Element render(const QString &url, int rows);
    /** The columns [rows] rows of a square picture take, from the terminal's cell size. */
    int columnsFor(int rows) const;

private:
    void fetch(const QString &url);
    void cellSize(int *width, int *height) const;

    std::function<void()> m_loaded;
    QNetworkAccessManager m_network;
    QHash<QString, QImage> m_images;
    QSet<QString> m_pending;
    QSet<QString> m_failed;
    /** Conversions by cover and size (a page's, the bar's and Now Playing's can all show). */
    QHash<QString, std::vector<CoverCell>> m_cells;
    QStringList m_cellsOrder;
};
