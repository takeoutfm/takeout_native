#include "CoverCells.h"

#include <chafa.h>

std::vector<CoverCell> coverCells(const uint8_t *rgba, int width, int height, int stride,
                                  int cols, int rows, int cellWidth, int cellHeight)
{
    // Block elements (halves, quadrants, shades) and spaces: in every monospace font, unlike
    // sextants or braille.
    ChafaSymbolMap *symbols = chafa_symbol_map_new();
    chafa_symbol_map_add_by_tags(symbols, ChafaSymbolTags(CHAFA_SYMBOL_TAG_BLOCK | CHAFA_SYMBOL_TAG_SPACE));
    chafa_symbol_map_remove_by_tags(symbols, ChafaSymbolTags(CHAFA_SYMBOL_TAG_WIDE | CHAFA_SYMBOL_TAG_AMBIGUOUS));

    ChafaCanvasConfig *config = chafa_canvas_config_new();
    chafa_canvas_config_set_geometry(config, cols, rows);
    chafa_canvas_config_set_cell_geometry(config, cellWidth, cellHeight);
    chafa_canvas_config_set_canvas_mode(config, CHAFA_CANVAS_MODE_TRUECOLOR);
    chafa_canvas_config_set_symbol_map(config, symbols);
    chafa_canvas_config_set_work_factor(config, 0.8f);

    ChafaCanvas *canvas = chafa_canvas_new(config);
    chafa_canvas_draw_all_pixels(canvas, CHAFA_PIXEL_RGBA8_UNASSOCIATED, rgba, width, height, stride);

    std::vector<CoverCell> cells;
    cells.reserve(size_t(cols) * rows);
    for (int y = 0; y < rows; ++y) {
        for (int x = 0; x < cols; ++x) {
            CoverCell cell;
            gchar utf8[8] = {};
            g_unichar_to_utf8(chafa_canvas_get_char_at(canvas, x, y), utf8);
            cell.glyph = utf8;
            chafa_canvas_get_colors_at(canvas, x, y, &cell.fg, &cell.bg);
            cells.push_back(cell);
        }
    }
    chafa_canvas_unref(canvas);
    chafa_canvas_config_unref(config);
    chafa_symbol_map_unref(symbols);
    return cells;
}
