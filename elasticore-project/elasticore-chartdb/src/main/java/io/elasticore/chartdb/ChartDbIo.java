package io.elasticore.chartdb;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A ChartDB diagram with names where the file has ids.
 *
 * In the file a relationship points at its tables and fields by id, an index
 * at its fields, a table at its area. The ids are ChartDB's own bookkeeping -
 * it assigns new ones on every export and on every import (clone.ts) - so
 * they cannot be what a diagram is compared or converted by. {@link #named}
 * replaces each id reference with the name it stands for and drops the ids;
 * {@link #diagram} goes back, numbering the elements in the order ChartDB's
 * own export numbers them and writing each element's keys in the order of its
 * interface declaration (src/lib/domain/*.ts).
 *
 * The named form, element by element (optional keys are present only where
 * the file has them):
 * <pre>
 * diagram       id name databaseType databaseEdition tables relationships dependencies areas customTypes notes createdAt updatedAt
 * table         name schema x y fields indexes checkConstraints(expressions) color isView isMaterializedView
 *               width comments order expanded parentArea(index into areas) | parentAreaId(an id no area has)
 * field         name type{id,name} primaryKey unique nullable increment isArray characterMaximumLength precision
 *               scale default collation comments check
 * index         name unique fields(names) type isPrimaryKey comments
 * relationship  name sourceSchema sourceTable(key) targetSchema targetTable(key) sourceField targetField
 *               sourceCardinality targetCardinality
 * dependency    schema table(key) dependentSchema dependentTable(key)
 * area, note, custom type: as in the file, without the id
 * </pre>
 * A table key is {@code schema.table}, or the table name where it has no schema.
 */
final class ChartDbIo {

    private static final List<String> DIAGRAM = Arrays.asList("id", "name", "databaseType", "databaseEdition",
            "tables", "relationships", "dependencies", "areas", "customTypes", "notes", "createdAt", "updatedAt");
    private static final List<String> TABLE = Arrays.asList("id", "name", "schema", "x", "y", "fields", "indexes",
            "checkConstraints", "color", "isView", "isMaterializedView", "createdAt", "width", "comments", "order",
            "expanded", "parentAreaId");
    private static final List<String> FIELD = Arrays.asList("id", "name", "type", "primaryKey", "unique",
            "nullable", "increment", "isArray", "createdAt", "characterMaximumLength", "precision", "scale",
            "default", "collation", "comments", "check");
    private static final List<String> INDEX = Arrays.asList("id", "name", "unique", "fieldIds", "createdAt", "type",
            "isPrimaryKey", "comments");
    private static final List<String> RELATIONSHIP = Arrays.asList("id", "name", "sourceSchema", "sourceTableId",
            "targetSchema", "targetTableId", "sourceFieldId", "targetFieldId", "sourceCardinality",
            "targetCardinality", "createdAt");
    private static final List<String> DEPENDENCY = Arrays.asList("id", "schema", "tableId", "dependentSchema",
            "dependentTableId", "createdAt");
    private static final List<String> AREA = Arrays.asList("id", "name", "x", "y", "width", "height", "color",
            "order");
    private static final List<String> NOTE = Arrays.asList("id", "content", "x", "y", "width", "height", "color",
            "order");
    private static final List<String> CUSTOM_TYPE = Arrays.asList("id", "schema", "name", "kind", "values", "fields",
            "order");

    private ChartDbIo() {
    }

    // ---- file -> named -------------------------------------------------------------

    /** What the file says, by name. Problems the file has - a reference to nothing - are added to {@code warnings}. */
    static Map<String, Object> named(Map<String, Object> file, List<String> warnings) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        Map<String, Integer> areaIndex = new HashMap<String, Integer>();
        List<Map<String, Object>> areas = maps(file.get("areas"));
        for (int i = 0; i < areas.size(); i++) areaIndex.put(String.valueOf(areas.get(i).get("id")), i);

        Map<String, String> tableKey = new HashMap<String, String>();      // table id -> key
        Map<String, String> fieldName = new HashMap<String, String>();     // field id -> name
        for (Map<String, Object> t : maps(file.get("tables"))) {
            tableKey.put(String.valueOf(t.get("id")), Names.key(t.get("schema"), t.get("name")));
            for (Map<String, Object> f : maps(t.get("fields"))) {
                fieldName.put(String.valueOf(f.get("id")), String.valueOf(f.get("name")));
            }
        }

        for (Map.Entry<String, Object> e : file.entrySet()) {
            String key = e.getKey();
            if ("tables".equals(key)) {
                List<Object> tables = new ArrayList<Object>();
                for (Map<String, Object> t : maps(e.getValue())) tables.add(namedTable(t, areaIndex, fieldName, warnings));
                out.put(key, tables);
            } else if ("relationships".equals(key)) {
                List<Object> list = new ArrayList<Object>();
                for (Map<String, Object> r : maps(e.getValue())) {
                    Map<String, Object> n = new LinkedHashMap<String, Object>();
                    boolean whole = true;
                    for (Map.Entry<String, Object> a : r.entrySet()) {
                        String k = a.getKey();
                        String id = String.valueOf(a.getValue());
                        if ("id".equals(k) || "createdAt".equals(k)) continue;
                        if ("sourceTableId".equals(k) || "targetTableId".equals(k)) {
                            whole &= tableKey.containsKey(id);
                            n.put(k.substring(0, k.length() - 2), tableKey.get(id));
                        } else if ("sourceFieldId".equals(k) || "targetFieldId".equals(k)) {
                            whole &= fieldName.containsKey(id);
                            n.put(k.substring(0, k.length() - 2), fieldName.get(id));
                        } else {
                            n.put(k, a.getValue());
                        }
                    }
                    if (whole) {
                        list.add(n);
                    } else {
                        warnings.add("relationship " + r.get("name") + " refers to a table or field the diagram"
                                + " does not have; ChartDB drops it on import, and so does this");
                    }
                }
                out.put(key, list);
            } else if ("dependencies".equals(key)) {
                List<Object> list = new ArrayList<Object>();
                for (Map<String, Object> d : maps(e.getValue())) {
                    Map<String, Object> n = new LinkedHashMap<String, Object>();
                    boolean whole = true;
                    for (Map.Entry<String, Object> a : d.entrySet()) {
                        String k = a.getKey();
                        if ("id".equals(k) || "createdAt".equals(k)) continue;
                        if ("tableId".equals(k) || "dependentTableId".equals(k)) {
                            String id = String.valueOf(a.getValue());
                            whole &= tableKey.containsKey(id);
                            n.put(k.substring(0, k.length() - 2), tableKey.get(id));
                        } else {
                            n.put(k, a.getValue());
                        }
                    }
                    if (whole) {
                        list.add(n);
                    } else {
                        warnings.add("a dependency refers to a table the diagram does not have; it is dropped");
                    }
                }
                out.put(key, list);
            } else if ("areas".equals(key) || "notes".equals(key) || "customTypes".equals(key)) {
                List<Object> list = new ArrayList<Object>();
                for (Map<String, Object> a : maps(e.getValue())) list.add(without(a, "id"));
                out.put(key, list);
            } else {
                out.put(key, e.getValue());
            }
        }
        return out;
    }

    private static Map<String, Object> namedTable(Map<String, Object> t, Map<String, Integer> areaIndex,
                                                  Map<String, String> fieldName, List<String> warnings) {
        Map<String, Object> n = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> a : t.entrySet()) {
            String k = a.getKey();
            if ("id".equals(k) || "createdAt".equals(k)) continue;
            if ("fields".equals(k)) {
                List<Object> fields = new ArrayList<Object>();
                for (Map<String, Object> f : maps(a.getValue())) fields.add(without(without(f, "id"), "createdAt"));
                n.put(k, fields);
            } else if ("indexes".equals(k)) {
                List<Object> indexes = new ArrayList<Object>();
                for (Map<String, Object> ix : maps(a.getValue())) {
                    Map<String, Object> i = new LinkedHashMap<String, Object>();
                    for (Map.Entry<String, Object> p : ix.entrySet()) {
                        if ("id".equals(p.getKey()) || "createdAt".equals(p.getKey())) continue;
                        if ("fieldIds".equals(p.getKey())) {
                            List<Object> names = new ArrayList<Object>();
                            for (Object id : list(p.getValue())) {
                                String name = fieldName.get(String.valueOf(id));
                                if (name != null) {
                                    names.add(name);
                                } else {
                                    warnings.add("index " + ix.get("name") + " of " + t.get("name")
                                            + " names a field the table does not have");
                                }
                            }
                            i.put("fields", names);
                        } else {
                            i.put(p.getKey(), p.getValue());
                        }
                    }
                    indexes.add(i);
                }
                n.put(k, indexes);
            } else if ("checkConstraints".equals(k) && a.getValue() != null) {
                List<Object> expressions = new ArrayList<Object>();
                for (Map<String, Object> c : maps(a.getValue())) expressions.add(c.get("expression"));
                n.put(k, expressions);
            } else if ("parentAreaId".equals(k) && a.getValue() != null) {
                // An id no area has is what ChartDB's own export leaves: it renumbers the areas and
                // not this reference. Kept as it is - ChartDB finds the area from the position.
                Integer index = areaIndex.get(String.valueOf(a.getValue()));
                if (index != null) {
                    n.put("parentArea", index);
                } else {
                    n.put(k, a.getValue());
                }
            } else {
                n.put(k, a.getValue());
            }
        }
        return n;
    }

    /** The createdAt most elements of the file carry: the one a converted diagram gives all of them. */
    static long stamp(Map<String, Object> file) {
        for (Map<String, Object> t : maps(file.get("tables"))) {
            if (t.get("createdAt") instanceof Number) return ((Number) t.get("createdAt")).longValue();
        }
        return System.currentTimeMillis();
    }

    // ---- named -> file -------------------------------------------------------------

    /** The diagram file of a named diagram: ChartDB's export numbering, one createdAt for every element. */
    static Map<String, Object> diagram(Map<String, Object> named, long stamp) {
        int[] next = {1};
        List<Map<String, Object>> namedTables = maps(named.get("tables"));
        List<Map<String, Object>> namedAreas = maps(named.get("areas"));

        // Ids in the order clone.ts hands them out: every table with its fields and indexes, then
        // relationships, dependencies, areas, notes, custom types.
        Map<String, String> tableId = new HashMap<String, String>();
        Map<String, String> fieldId = new HashMap<String, String>();        // table key + '\n' + field
        List<String> tableIds = new ArrayList<String>();
        List<List<String>> fieldIds = new ArrayList<List<String>>();
        List<List<String>> indexIds = new ArrayList<List<String>>();
        for (Map<String, Object> t : namedTables) {
            String key = Names.key(t.get("schema"), t.get("name"));
            String id = String.valueOf(next[0]++);
            tableIds.add(id);
            if (!tableId.containsKey(key)) tableId.put(key, id);
            List<String> fids = new ArrayList<String>();
            for (Map<String, Object> f : maps(t.get("fields"))) {
                String fid = String.valueOf(next[0]++);
                fids.add(fid);
                String fk = key + "\n" + f.get("name");
                if (!fieldId.containsKey(fk)) fieldId.put(fk, fid);
            }
            fieldIds.add(fids);
            List<String> iids = new ArrayList<String>();
            for (int i = 0; i < maps(t.get("indexes")).size(); i++) iids.add(String.valueOf(next[0]++));
            indexIds.add(iids);
        }
        List<String> relationshipIds = ids(named.get("relationships"), next);
        List<String> dependencyIds = ids(named.get("dependencies"), next);
        List<String> areaIds = ids(named.get("areas"), next);
        List<String> noteIds = ids(named.get("notes"), next);
        List<String> typeIds = ids(named.get("customTypes"), next);

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (String k : ordered(named.keySet(), DIAGRAM)) {
            Object v = named.get(k);
            if ("id".equals(k)) {
                out.put(k, v == null ? "0" : v);
            } else if ("tables".equals(k)) {
                List<Object> tables = new ArrayList<Object>();
                for (int i = 0; i < namedTables.size(); i++) {
                    tables.add(table(namedTables.get(i), tableIds.get(i), fieldIds.get(i), indexIds.get(i), areaIds,
                            stamp));
                }
                out.put(k, tables);
            } else if ("relationships".equals(k)) {
                List<Object> list = new ArrayList<Object>();
                List<Map<String, Object>> rs = maps(v);
                for (int i = 0; i < rs.size(); i++) {
                    Map<String, Object> r = rs.get(i);
                    Map<String, Object> raw = new LinkedHashMap<String, Object>(r);
                    raw.put("id", relationshipIds.get(i));
                    raw.put("createdAt", stamp);
                    for (String side : new String[]{"source", "target"}) {
                        String table = String.valueOf(raw.remove(side + "Table"));
                        raw.put(side + "TableId", tableId.get(table));
                        raw.put(side + "FieldId", fieldId.get(table + "\n" + raw.remove(side + "Field")));
                    }
                    list.add(inOrder(raw, RELATIONSHIP));
                }
                out.put(k, list);
            } else if ("dependencies".equals(k)) {
                List<Object> list = new ArrayList<Object>();
                List<Map<String, Object>> ds = maps(v);
                for (int i = 0; i < ds.size(); i++) {
                    Map<String, Object> raw = new LinkedHashMap<String, Object>(ds.get(i));
                    raw.put("id", dependencyIds.get(i));
                    raw.put("createdAt", stamp);
                    raw.put("tableId", tableId.get(String.valueOf(raw.remove("table"))));
                    raw.put("dependentTableId", tableId.get(String.valueOf(raw.remove("dependentTable"))));
                    list.add(inOrder(raw, DEPENDENCY));
                }
                out.put(k, list);
            } else if ("areas".equals(k)) {
                out.put(k, withIds(v, areaIds, AREA));
            } else if ("notes".equals(k)) {
                out.put(k, withIds(v, noteIds, NOTE));
            } else if ("customTypes".equals(k)) {
                out.put(k, withIds(v, typeIds, CUSTOM_TYPE));
            } else {
                out.put(k, v);
            }
        }
        if (!out.containsKey("id")) {
            Map<String, Object> withId = new LinkedHashMap<String, Object>();
            withId.put("id", "0");
            withId.putAll(out);
            out = withId;
        }
        return out;
    }

    private static Map<String, Object> table(Map<String, Object> t, String id, List<String> fieldIds,
                                             List<String> indexIds, List<String> areaIds, long stamp) {
        Map<String, Object> raw = new LinkedHashMap<String, Object>();
        raw.put("id", id);
        raw.put("createdAt", stamp);
        Map<String, String> byName = new HashMap<String, String>();
        for (Map.Entry<String, Object> a : t.entrySet()) {
            String k = a.getKey();
            if ("fields".equals(k)) {
                List<Object> fields = new ArrayList<Object>();
                List<Map<String, Object>> fs = maps(a.getValue());
                for (int i = 0; i < fs.size(); i++) {
                    Map<String, Object> f = new LinkedHashMap<String, Object>(fs.get(i));
                    f.put("id", fieldIds.get(i));
                    f.put("createdAt", stamp);
                    if (!byName.containsKey(String.valueOf(f.get("name")))) {
                        byName.put(String.valueOf(f.get("name")), fieldIds.get(i));
                    }
                    fields.add(inOrder(f, FIELD));
                }
                raw.put(k, fields);
            } else if ("indexes".equals(k)) {
                List<Object> indexes = new ArrayList<Object>();
                List<Map<String, Object>> is = maps(a.getValue());
                for (int i = 0; i < is.size(); i++) {
                    Map<String, Object> ix = new LinkedHashMap<String, Object>(is.get(i));
                    ix.put("id", indexIds.get(i));
                    ix.put("createdAt", stamp);
                    List<Object> ids = new ArrayList<Object>();
                    for (Object name : list(ix.remove("fields"))) {
                        String fid = byName.get(String.valueOf(name));
                        if (fid != null) ids.add(fid);
                    }
                    ix.put("fieldIds", ids);
                    indexes.add(inOrder(ix, INDEX));
                }
                raw.put(k, indexes);
            } else if ("checkConstraints".equals(k) && a.getValue() != null) {
                raw.put(k, a.getValue());      // numbered below, after the indexes
            } else if ("parentArea".equals(k)) {
                int index = ((Number) a.getValue()).intValue();
                raw.put("parentAreaId", index >= 0 && index < areaIds.size() ? areaIds.get(index) : null);
            } else {
                raw.put(k, a.getValue());
            }
        }
        if (raw.get("checkConstraints") instanceof List) {
            List<Object> checks = new ArrayList<Object>();
            int n = 0;
            for (Object expression : list(raw.get("checkConstraints"))) {
                Map<String, Object> c = new LinkedHashMap<String, Object>();
                // Not numbered by ChartDB's export either: it leaves a check constraint's id as it is.
                c.put("id", id + "-check-" + (++n));
                c.put("expression", expression);
                c.put("createdAt", stamp);
                checks.add(c);
            }
            raw.put("checkConstraints", checks);
        }
        return inOrder(raw, TABLE);
    }

    private static List<String> ids(Object elements, int[] next) {
        List<String> ids = new ArrayList<String>();
        for (int i = 0; i < list(elements).size(); i++) ids.add(String.valueOf(next[0]++));
        return ids;
    }

    private static List<Object> withIds(Object elements, List<String> ids, List<String> order) {
        List<Object> out = new ArrayList<Object>();
        List<Map<String, Object>> list = maps(elements);
        for (int i = 0; i < list.size(); i++) {
            Map<String, Object> raw = new LinkedHashMap<String, Object>(list.get(i));
            raw.put("id", ids.get(i));
            out.add(inOrder(raw, order));
        }
        return out;
    }

    /** The keys of {@code order} that the map has, then the keys it has that {@code order} does not know. */
    private static Map<String, Object> inOrder(Map<String, Object> raw, List<String> order) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (String k : ordered(raw.keySet(), order)) out.put(k, raw.get(k));
        return out;
    }

    private static List<String> ordered(java.util.Collection<String> keys, List<String> order) {
        List<String> out = new ArrayList<String>();
        for (String k : order) if (keys.contains(k)) out.add(k);
        for (String k : keys) if (!out.contains(k)) out.add(k);
        return out;
    }

    // ---- trees ---------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> maps(Object value) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        if (value instanceof List) {
            for (Object o : (List<Object>) value) if (o instanceof Map) out.add((Map<String, Object>) o);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object value) {
        return value instanceof List ? (List<Object>) value : new ArrayList<Object>();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<String, Object>();
    }

    private static Map<String, Object> without(Map<String, Object> map, String key) {
        Map<String, Object> out = new LinkedHashMap<String, Object>(map);
        out.remove(key);
        return out;
    }
}
