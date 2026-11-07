#include "Cover.h"

#include <QNetworkDiskCache>
#include <QNetworkReply>
#include <QStandardPaths>
#include <sys/ioctl.h>
#include <unistd.h>

using namespace ftxui;

Cover::Cover(std::function<void()> loaded)
    : m_loaded(std::move(loaded))
{
    auto *cache = new QNetworkDiskCache(&m_network);
    cache->setCacheDirectory(QStandardPaths::writableLocation(QStandardPaths::GenericCacheLocation) + "/tofm/covers");
    cache->setMaximumCacheSize(100 * 1024 * 1024);
    m_network.setCache(cache);
}

void Cover::cellSize(int *width, int *height) const
{
    // The terminal's size in pixels, where it says (most do); else the usual 1:2 cell.
    winsize size {};
    if (::ioctl(STDOUT_FILENO, TIOCGWINSZ, &size) == 0 && size.ws_col && size.ws_row && size.ws_xpixel && size.ws_ypixel) {
        *width = size.ws_xpixel / size.ws_col;
        *height = size.ws_ypixel / size.ws_row;
    } else {
        *width = 8;
        *height = 16;
    }
    // Once, for the log: what the terminal said (why covers look the shape they do).
    static bool logged = false;
    if (!logged) {
        logged = true;
        qWarning("tofm: terminal %dx%d cells, %dx%d pixels: a cell is %dx%d", size.ws_col, size.ws_row,
                 size.ws_xpixel, size.ws_ypixel, *width, *height);
    }
}

int Cover::columnsFor(int rows) const
{
    int width = 0, height = 0;
    cellSize(&width, &height);
    return std::max(1, rows * height / std::max(1, width));
}

void Cover::fetch(const QString &url)
{
    if (url.isEmpty() || m_pending.contains(url) || m_failed.contains(url)) return;
    m_pending.insert(url);
    QNetworkRequest request{QUrl(url)};
    request.setAttribute(QNetworkRequest::CacheLoadControlAttribute, QNetworkRequest::PreferCache);
    request.setAttribute(QNetworkRequest::RedirectPolicyAttribute, QNetworkRequest::NoLessSafeRedirectPolicy);
    QNetworkReply *reply = m_network.get(request);
    QObject::connect(reply, &QNetworkReply::finished, reply, [this, reply, url] {
        reply->deleteLater();
        m_pending.remove(url);
        QImage image;
        if (reply->error() == QNetworkReply::NoError) image.loadFromData(reply->readAll());
        if (image.isNull()) {
            m_failed.insert(url);
            return;
        }
        m_images.insert(url, image.convertToFormat(QImage::Format_RGBA8888));
        m_loaded();
    });
}

Element Cover::render(const QString &url, int rows)
{
    const int cols = columnsFor(rows);
    const auto blank = [&] { return text("") | size(WIDTH, EQUAL, cols) | size(HEIGHT, EQUAL, rows); };
    if (rows <= 0 || cols <= 0) return text("");
    if (!m_images.contains(url)) {
        fetch(url);
        return blank();
    }
    const QString key = QStringLiteral("%1 %2x%3").arg(url).arg(cols).arg(rows);
    if (!m_cells.contains(key)) {
        const QImage &image = m_images[url];
        int cellWidth = 0, cellHeight = 0;
        cellSize(&cellWidth, &cellHeight);
        m_cells.insert(key, coverCells(image.constBits(), image.width(), image.height(), int(image.bytesPerLine()),
                                       cols, rows, cellWidth, cellHeight));
        m_cellsOrder << key;
        // A handful are enough: what's on screen now.
        while (m_cellsOrder.size() > 8) m_cells.remove(m_cellsOrder.takeFirst());
    }
    const std::vector<CoverCell> &cells = m_cells[key];
    if (cells.size() != size_t(cols) * rows) return blank();
    Elements lines;
    for (int y = 0; y < rows; ++y) {
        Elements line;
        for (int x = 0; x < cols; ++x) {
            const CoverCell &cell = cells[size_t(y) * cols + x];
            Element glyph = text(cell.glyph);
            if (cell.fg >= 0) glyph = glyph | color(Color::RGB((cell.fg >> 16) & 0xff, (cell.fg >> 8) & 0xff, cell.fg & 0xff));
            if (cell.bg >= 0) glyph = glyph | bgcolor(Color::RGB((cell.bg >> 16) & 0xff, (cell.bg >> 8) & 0xff, cell.bg & 0xff));
            line.push_back(glyph);
        }
        lines.push_back(hbox(line));
    }
    return vbox(lines);
}
