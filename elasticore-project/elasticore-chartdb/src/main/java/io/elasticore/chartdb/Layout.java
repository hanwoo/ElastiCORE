package io.elasticore.chartdb;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where tables go when nothing says where.
 *
 * A blueprint written by hand has no positions. Its tables are set out in
 * columns, shortest column first; several domains (or, in one domain, several
 * schemas) each get an area with their tables inside, since that grouping is
 * the one thing the model does say about the picture.
 */
final class Layout {

    private static final String[] AREA_COLORS = {"#b067e9", "#8eb7ff", "#4dee8a", "#ffe374", "#ff9f74", "#42e0c0",
            "#ff6b8a", "#7175fa"};
    private static final int TABLE_WIDTH = 337, COLUMN_GAP = 60, ROW_GAP = 60, PAD = 40, HEADER = 70, AREA_GAP = 120;
    /** ChartDB shows this many fields of a table that is not expanded. */
    private static final int SHOWN_FIELDS = 10;

    private Layout() {
    }

    static int height(Map<String, Object> table) {
        int fields = ChartDbIo.list(table.get("fields")).size();
        if (!Boolean.TRUE.equals(table.get("expanded")) && fields > SHOWN_FIELDS) fields = SHOWN_FIELDS + 1;
        return 44 + 32 * fields;
    }

    /**
     * Places every table of the diagram.
     *
     * @param groups group name -> table keys; one group draws no area
     */
    static void arrange(Map<String, Object> diagram, Map<String, List<String>> groups) {
        Map<String, Map<String, Object>> byKey = new LinkedHashMap<String, Map<String, Object>>();
        List<Map<String, Object>> tables = ChartDbIo.maps(diagram.get("tables"));
        List<String> keys = Sidecar.keys(tables, Sidecar.TABLE);
        for (int i = 0; i < tables.size(); i++) byKey.put(keys.get(i), tables.get(i));

        boolean areas = groups.size() > 1;
        List<Object> areaList = ChartDbIo.list(diagram.get("areas"));
        int x = 0, y = 0, rowBottom = 0, n = 0;
        int perRow = groups.size() <= 4 ? 2 : 3;
        for (Map.Entry<String, List<String>> g : groups.entrySet()) {
            List<Map<String, Object>> members = new ArrayList<Map<String, Object>>();
            for (String key : g.getValue()) if (byKey.containsKey(key)) members.add(byKey.get(key));
            if (members.isEmpty()) continue;
            if (n > 0 && n % perRow == 0) {
                x = 0;
                y = rowBottom + AREA_GAP;
            }
            int columns = !areas ? Math.min(5, Math.max(1, members.size())) : members.size() > 6 ? 3 : members.size() > 1 ? 2 : 1;
            int[] size = columns(members, x, y, columns, areas ? areaList.size() : -1);
            if (areas) {
                Map<String, Object> area = new LinkedHashMap<String, Object>();
                area.put("name", g.getKey());
                area.put("x", x);
                area.put("y", y);
                area.put("width", size[0]);
                area.put("height", size[1]);
                area.put("color", AREA_COLORS[areaList.size() % AREA_COLORS.length]);
                area.put("order", areaList.size());
                areaList.add(area);
            }
            x += size[0] + AREA_GAP;
            rowBottom = Math.max(rowBottom, y + size[1]);
            n++;
        }
        diagram.put("areas", areaList);
    }

    /** Sets the tables out in columns from (x, y); returns the width and height they take, padding included. */
    private static int[] columns(List<Map<String, Object>> tables, int x, int y, int columns, int area) {
        int[] columnY = new int[columns];
        for (int c = 0; c < columns; c++) columnY[c] = y + HEADER;
        for (Map<String, Object> t : tables) {
            int c = 0;
            for (int i = 1; i < columns; i++) if (columnY[i] < columnY[c]) c = i;
            t.put("x", x + PAD + c * (TABLE_WIDTH + COLUMN_GAP));
            t.put("y", columnY[c]);
            if (area >= 0) t.put("parentArea", area);
            columnY[c] += height(t) + ROW_GAP;
        }
        int bottom = 0;
        for (int c = 0; c < columns; c++) bottom = Math.max(bottom, columnY[c]);
        return new int[]{PAD * 2 + columns * TABLE_WIDTH + (columns - 1) * COLUMN_GAP, bottom - y - ROW_GAP + PAD};
    }

    /** Tables the sidecar has no place for - added to the blueprint since - in a row under the picture. */
    static void placeNew(Map<String, Object> diagram, List<String> placedKeys) {
        List<Map<String, Object>> tables = ChartDbIo.maps(diagram.get("tables"));
        List<String> keys = Sidecar.keys(tables, Sidecar.TABLE);
        double bottom = 0, left = 0;
        boolean any = false;
        List<Map<String, Object>> fresh = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < tables.size(); i++) {
            Map<String, Object> t = tables.get(i);
            if (placedKeys.contains(keys.get(i))) {
                double y = number(t.get("y")) + height(t);
                bottom = any ? Math.max(bottom, y) : y;
                left = any ? Math.min(left, number(t.get("x"))) : number(t.get("x"));
                any = true;
            } else {
                fresh.add(t);
            }
        }
        for (Map<String, Object> a : ChartDbIo.maps(diagram.get("areas"))) {
            bottom = Math.max(bottom, number(a.get("y")) + number(a.get("height")));
        }
        int x = (int) left;
        for (Map<String, Object> t : fresh) {
            t.put("x", x);
            t.put("y", (int) bottom + (any ? AREA_GAP : 0));
            x += TABLE_WIDTH + COLUMN_GAP;
        }
    }

    private static double number(Object value) {
        return value instanceof Number ? ((Number) value).doubleValue() : 0;
    }
}
