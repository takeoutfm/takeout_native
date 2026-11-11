#pragma once

#include <cstdint>
#include <string>
#include <vector>

// A picture as terminal cells, by libchafa: a character with its foreground and background
// colours (0xRRGGBB, or -1 for none). Plain C++, so GLib's headers never meet Qt's macros.
struct CoverCell {
    std::string glyph;
    int fg = -1;
    int bg = -1;
};

/**
 * [rgba] (width × height, [stride] bytes a row, 8 bits each of R, G, B, A) drawn into a
 * [cols] × [rows] grid of block characters in 24-bit colour; [cellWidth] / [cellHeight] are a
 * cell's size in pixels (for the aspect), so a square picture fills a square-looking area.
 * Row by row.
 */
std::vector<CoverCell> coverCells(const uint8_t *rgba, int width, int height, int stride,
                                  int cols, int rows, int cellWidth, int cellHeight);
