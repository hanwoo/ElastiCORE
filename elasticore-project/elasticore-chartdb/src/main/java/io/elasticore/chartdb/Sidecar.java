package io.elasticore.chartdb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a diagram says that its blueprint does not: {@code chartdb.yml}.
 *
 * The DSL describes a model - tables, columns, keys, references. A diagram
 * also says where each table is drawn and in what colour, groups tables into
 * areas, carries notes, and knows things about a database the DSL has no word
 * for (an index's type, a collation, a materialized view). Converting a
 * diagram to a blueprint and back must not lose them, so they are kept beside
 * the blueprint.
 *
 * The sidecar is not a second description of the model. It is the difference
 * between the diagram and what the blueprint alone yields ({@link DslReader}),
 * computed when the blueprint is written: {@link #diff} compares the two
 * element by element and keeps each attribute the blueprint did not
 * reproduce; {@link #apply} puts them back. An attribute the DSL does carry
 * is therefore never in the sidecar, and editing the DSL - a new column, a
 * renamed table - shows in the next diagram without touching this file.
 *
 * <pre>
 * chartdb:
 *   version: 1
 *   stamp: 1793836800000            # createdAt of every element
 *   diagram: { id, name, databaseType, ... }
 *   typeNames: { DslName: database_type_name }     # only where the DSL name does not give it
 *   tables:                         # by schema.table
 *     catalog.products:
 *       x: 100                      # an attribute the blueprint did not give, or gave differently
 *       $unset: [comments]          # one it gave and the diagram does not have
 *       fields:   { name: { collation: ko-KR-x-icu } }
 *       indexes:  { idx_a: { type: gin }, "&lt;pk&gt;": { $new: true, ... } }
 *     $order: [ ... ]               # where the diagram's order is not the blueprint's
 *     $omit:  [ ... ]               # what the blueprint has and the diagram does not
 *   relationships: { "sales.orders.user_id&gt;member.users.id": { name: fk_orders_user } }
 *   customTypes:   { order_status: { schema: sales, order: 5 } }
 *   areas: [ ... ]    notes: [ ... ]    dependencies: [ ... ]
 * </pre>
 */
final class Sidecar {

    static final String FILE_NAME = "chartdb.yml";

    /** What only the picture knows: where, how large, what colour, in which area. */
    static final Set<String> LAYOUT = new HashSet<String>(Arrays.asList(
            "x", "y", "color", "width", "order", "expanded", "parentArea", "parentAreaId"));

    private Sidecar() {
    }

    // ---- keys ----------------------------------------------------------------------------

    interface Key {
        String of(Map<String, Object> element, int position);
    }

    static final Key TABLE = new Key() {
        public String of(Map<String, Object> t, int position) {
            return Names.key(t.get("schema"), t.get("name"));
        }
    };
    static final Key NAME = new Key() {
        public String of(Map<String, Object> e, int position) {
            return String.valueOf(e.get("name"));
        }
    };
    static final Key INDEX = new Key() {
        public String of(Map<String, Object> ix, int position) {
            if (Boolean.TRUE.equals(ix.get("isPrimaryKey"))) return "<pk>";
            Object name = ix.get("name");
            if (name != null && !String.valueOf(name).isEmpty()) return String.valueOf(name);
            StringBuilder b = new StringBuilder("(");
            for (Object f : ChartDbIo.list(ix.get("fields"))) b.append(b.length() > 1 ? "," : "").append(f);
            return b.append(')').toString();
        }
    };
    /** A relationship by the column that holds the foreign key, whichever end the diagram drew it from. */
    static final Key RELATIONSHIP = new Key() {
        public String of(Map<String, Object> r, int position) {
            String source = r.get("sourceTable") + "." + r.get("sourceField");
            String target = r.get("targetTable") + "." + r.get("targetField");
            boolean fromOne = "one".equals(r.get("sourceCardinality")) && "many".equals(r.get("targetCardinality"));
            return fromOne ? target + ">" + source : source + ">" + target;
        }
    };

    /** The key of each element; a second element with a key already taken gets {@code key#2}. */
    static List<String> keys(List<Map<String, Object>> elements, Key key) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < elements.size(); i++) {
            String k = key.of(elements.get(i), i);
            String unique = k;
            for (int n = 2; out.contains(unique); n++) unique = k + "#" + n;
            out.add(unique);
        }
        return out;
    }

    // ---- diff ----------------------------------------------------------------------------

    /**
     * @param original  the diagram, named
     * @param derived   what the blueprint written from it yields, named
     * @param typeNames custom type name in the diagram -> the DSL name the writer gave it
     */
    static Map<String, Object> diff(Map<String, Object> original, Map<String, Object> derived,
                                    Map<String, String> typeNames, long stamp) {
        Map<String, Object> side = new LinkedHashMap<String, Object>();
        side.put("version", 1);
        side.put("stamp", stamp);

        Map<String, Object> diagram = new LinkedHashMap<String, Object>();
        List<Object> absent = new ArrayList<Object>();
        for (Map.Entry<String, Object> e : original.entrySet()) {
            if (!(e.getValue() instanceof List)) diagram.put(e.getKey(), e.getValue());
        }
        for (String k : derived.keySet()) if (!original.containsKey(k)) absent.add(k);
        if (!absent.isEmpty()) diagram.put("$unset", absent);
        side.put("diagram", diagram);

        // The names the diagram's custom types have, where the DSL name does not give them.
        Map<String, Object> irregular = new LinkedHashMap<String, Object>();
        Map<String, String> rename = new LinkedHashMap<String, String>();       // derived name -> diagram name
        for (Map.Entry<String, String> e : typeNames.entrySet()) {
            String derivedName = Names.snake(e.getValue());
            if (!derivedName.equals(e.getKey())) {
                irregular.put(e.getValue(), e.getKey());
                rename.put(derivedName, e.getKey());
            }
        }
        if (!irregular.isEmpty()) side.put("typeNames", irregular);
        derived = renameTypes(derived, rename);

        put(side, "tables", listDiff(ChartDbIo.maps(original.get("tables")), ChartDbIo.maps(derived.get("tables")),
                TABLE, true));
        put(side, "relationships", listDiff(ChartDbIo.maps(original.get("relationships")),
                ChartDbIo.maps(derived.get("relationships")), RELATIONSHIP, false));
        put(side, "customTypes", listDiff(ChartDbIo.maps(original.get("customTypes")),
                ChartDbIo.maps(derived.get("customTypes")), NAME, false));
        for (String k : new String[]{"areas", "notes", "dependencies"}) {
            if (!ChartDbIo.list(original.get(k)).isEmpty()) side.put(k, original.get(k));
        }
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("chartdb", side);
        return root;
    }

    private static void put(Map<String, Object> into, String key, Map<String, Object> patch) {
        if (!patch.isEmpty()) into.put(key, patch);
    }

    private static Map<String, Object> listDiff(List<Map<String, Object>> original, List<Map<String, Object>> derived,
                                                Key key, boolean table) {
        Map<String, Object> patch = new LinkedHashMap<String, Object>();
        List<String> originalKeys = keys(original, key);
        List<String> derivedKeys = keys(derived, key);
        List<Object> omit = new ArrayList<Object>();
        for (String k : derivedKeys) if (!originalKeys.contains(k)) omit.add(k);

        for (int i = 0; i < original.size(); i++) {
            String k = originalKeys.get(i);
            int at = derivedKeys.indexOf(k);
            Map<String, Object> item;
            if (at < 0) {
                item = new LinkedHashMap<String, Object>();
                item.put("$new", true);
                item.putAll(original.get(i));
            } else {
                item = itemDiff(original.get(i), derived.get(at), table);
            }
            if (!item.isEmpty()) patch.put(k, item);
        }
        // The order the diagram has them in, where it is not the one applying this patch would give.
        List<String> natural = new ArrayList<String>();
        for (String k : derivedKeys) if (originalKeys.contains(k)) natural.add(k);
        for (String k : originalKeys) if (!natural.contains(k)) natural.add(k);
        if (!natural.equals(originalKeys)) patch.put("$order", new ArrayList<Object>(originalKeys));
        if (!omit.isEmpty()) patch.put("$omit", omit);
        return patch;
    }

    private static Map<String, Object> itemDiff(Map<String, Object> original, Map<String, Object> derived,
                                                boolean table) {
        Map<String, Object> patch = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> e : original.entrySet()) {
            String k = e.getKey();
            if (table && ("fields".equals(k) || "indexes".equals(k))) {
                Map<String, Object> child = listDiff(ChartDbIo.maps(e.getValue()), ChartDbIo.maps(derived.get(k)),
                        "fields".equals(k) ? NAME : INDEX, false);
                if (!child.isEmpty()) patch.put(k, child);
            } else if (!derived.containsKey(k) || !same(e.getValue(), derived.get(k))) {
                patch.put(k, e.getValue());
            }
        }
        List<Object> unset = new ArrayList<Object>();
        for (String k : derived.keySet()) if (!original.containsKey(k)) unset.add(k);
        if (!unset.isEmpty()) patch.put("$unset", unset);
        return patch;
    }

    /** Equal as JSON: 5 and 5.0 are one number, and a map's key order is not part of it. */
    static boolean same(Object a, Object b) {
        if (a == null || b == null) return a == b;
        if (a instanceof Number && b instanceof Number) {
            return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue()) == 0;
        }
        if (a instanceof Map && b instanceof Map) {
            Map<?, ?> x = (Map<?, ?>) a, y = (Map<?, ?>) b;
            if (x.size() != y.size()) return false;
            for (Map.Entry<?, ?> e : x.entrySet()) {
                if (!y.containsKey(e.getKey()) || !same(e.getValue(), y.get(e.getKey()))) return false;
            }
            return true;
        }
        if (a instanceof List && b instanceof List) {
            List<?> x = (List<?>) a, y = (List<?>) b;
            if (x.size() != y.size()) return false;
            for (int i = 0; i < x.size(); i++) if (!same(x.get(i), y.get(i))) return false;
            return true;
        }
        return a.equals(b);
    }

    // ---- apply ---------------------------------------------------------------------------

    /**
     * The diagram: what the blueprint yields, with what the sidecar kept.
     *
     * @param dslNames custom type name the reader derived -> the DSL name it came from
     */
    static Map<String, Object> apply(Map<String, Object> derived, Map<String, Object> sidecar,
                                     Map<String, String> dslNames) {
        Map<String, Object> side = ChartDbIo.map(sidecar.get("chartdb"));
        Map<String, String> rename = new LinkedHashMap<String, String>();
        Map<String, Object> irregular = ChartDbIo.map(side.get("typeNames"));
        for (Map.Entry<String, String> e : dslNames.entrySet()) {
            Object diagramName = irregular.get(e.getValue());
            if (diagramName != null) rename.put(e.getKey(), String.valueOf(diagramName));
        }
        derived = renameTypes(derived, rename);

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        Map<String, Object> diagram = ChartDbIo.map(side.get("diagram"));
        for (Map.Entry<String, Object> e : diagram.entrySet()) {
            if (!"$unset".equals(e.getKey())) out.put(e.getKey(), e.getValue());
        }
        for (Map.Entry<String, Object> e : derived.entrySet()) {
            String k = e.getKey();
            if (ChartDbIo.list(diagram.get("$unset")).contains(k)) continue;
            if ("tables".equals(k)) {
                out.put(k, listApply(ChartDbIo.maps(e.getValue()), ChartDbIo.map(side.get(k)), TABLE, true));
            } else if ("relationships".equals(k)) {
                out.put(k, listApply(ChartDbIo.maps(e.getValue()), ChartDbIo.map(side.get(k)), RELATIONSHIP, false));
            } else if ("customTypes".equals(k)) {
                out.put(k, listApply(ChartDbIo.maps(e.getValue()), ChartDbIo.map(side.get(k)), NAME, false));
            } else if ("areas".equals(k) || "notes".equals(k) || "dependencies".equals(k)) {
                out.put(k, side.containsKey(k) ? side.get(k) : e.getValue());
            } else if (!out.containsKey(k)) {
                out.put(k, e.getValue());
            }
        }
        dropDangling(out);
        return out;
    }

    /** A dependency or a table's area the blueprint no longer has a table or a place for. */
    private static void dropDangling(Map<String, Object> diagram) {
        Set<String> tables = new HashSet<String>(keys(ChartDbIo.maps(diagram.get("tables")), TABLE));
        List<Object> kept = new ArrayList<Object>();
        for (Map<String, Object> d : ChartDbIo.maps(diagram.get("dependencies"))) {
            if (tables.contains(String.valueOf(d.get("table")))
                    && tables.contains(String.valueOf(d.get("dependentTable")))) kept.add(d);
        }
        if (diagram.containsKey("dependencies")) diagram.put("dependencies", kept);
    }

    private static List<Object> listApply(List<Map<String, Object>> derived, Map<String, Object> patch, Key key,
                                          boolean table) {
        List<String> derivedKeys = keys(derived, key);
        List<Object> omit = ChartDbIo.list(patch.get("$omit"));
        Map<String, Map<String, Object>> result = new LinkedHashMap<String, Map<String, Object>>();
        for (int i = 0; i < derived.size(); i++) {
            String k = derivedKeys.get(i);
            if (omit.contains(k)) continue;
            Map<String, Object> item = ChartDbIo.map(patch.get(k));
            result.put(k, patch.get(k) instanceof Map && !Boolean.TRUE.equals(item.get("$new"))
                    ? itemApply(derived.get(i), item, table)
                    : new LinkedHashMap<String, Object>(derived.get(i)));
        }
        for (Map.Entry<String, Object> e : patch.entrySet()) {
            if (e.getKey().startsWith("$") || !(e.getValue() instanceof Map)) continue;
            Map<String, Object> item = ChartDbIo.map(e.getValue());
            if (!Boolean.TRUE.equals(item.get("$new"))) continue;
            Map<String, Object> added = new LinkedHashMap<String, Object>(item);
            added.remove("$new");
            // The blueprint has since gained what the sidecar kept whole: the blueprint's is the newer.
            if (!result.containsKey(e.getKey())) result.put(e.getKey(), added);
        }
        List<Object> out = new ArrayList<Object>();
        for (Object k : ChartDbIo.list(patch.get("$order"))) {
            Map<String, Object> item = result.remove(String.valueOf(k));
            if (item != null) out.add(item);
        }
        out.addAll(result.values());
        return out;
    }

    private static Map<String, Object> itemApply(Map<String, Object> derived, Map<String, Object> patch,
                                                 boolean table) {
        Map<String, Object> out = new LinkedHashMap<String, Object>(derived);
        for (Object k : ChartDbIo.list(patch.get("$unset"))) out.remove(String.valueOf(k));
        for (Map.Entry<String, Object> e : patch.entrySet()) {
            String k = e.getKey();
            if (k.startsWith("$")) continue;
            if (table && ("fields".equals(k) || "indexes".equals(k))) {
                out.put(k, listApply(ChartDbIo.maps(derived.get(k)), ChartDbIo.map(e.getValue()),
                        "fields".equals(k) ? NAME : INDEX, false));
            } else {
                out.put(k, e.getValue());
            }
        }
        // An index names its fields: one the blueprint dropped is no longer the index's either.
        if (table) {
            Set<String> fields = new HashSet<String>(keys(ChartDbIo.maps(out.get("fields")), NAME));
            for (Map<String, Object> ix : ChartDbIo.maps(out.get("indexes"))) {
                List<Object> kept = new ArrayList<Object>();
                for (Object f : ChartDbIo.list(ix.get("fields"))) if (fields.contains(String.valueOf(f))) kept.add(f);
                ix.put("fields", kept);
            }
        }
        return out;
    }

    /** The diagram with its custom types, and every field of such a type, under other names. */
    private static Map<String, Object> renameTypes(Map<String, Object> diagram, Map<String, String> rename) {
        if (rename.isEmpty()) return diagram;
        Map<String, Object> out = new LinkedHashMap<String, Object>(diagram);
        List<Object> types = new ArrayList<Object>();
        for (Map<String, Object> t : ChartDbIo.maps(diagram.get("customTypes"))) {
            Map<String, Object> copy = new LinkedHashMap<String, Object>(t);
            String to = rename.get(String.valueOf(t.get("name")));
            if (to != null) copy.put("name", to);
            List<Object> fields = new ArrayList<Object>();
            for (Map<String, Object> f : ChartDbIo.maps(t.get("fields"))) {
                Map<String, Object> each = new LinkedHashMap<String, Object>(f);
                String type = rename.get(String.valueOf(f.get("type")));
                if (type != null) each.put("type", type);
                fields.add(each);
            }
            if (t.containsKey("fields") && t.get("fields") != null) copy.put("fields", fields);
            types.add(copy);
        }
        out.put("customTypes", types);
        List<Object> tables = new ArrayList<Object>();
        for (Map<String, Object> t : ChartDbIo.maps(diagram.get("tables"))) {
            Map<String, Object> copy = new LinkedHashMap<String, Object>(t);
            List<Object> fields = new ArrayList<Object>();
            for (Map<String, Object> f : ChartDbIo.maps(t.get("fields"))) {
                Map<String, Object> each = new LinkedHashMap<String, Object>(f);
                String to = rename.get(String.valueOf(ChartDbIo.map(f.get("type")).get("id")));
                if (to != null) {
                    Map<String, Object> type = new LinkedHashMap<String, Object>();
                    type.put("id", to);
                    type.put("name", to);
                    each.put("type", type);
                }
                fields.add(each);
            }
            copy.put("fields", fields);
            tables.add(copy);
        }
        out.put("tables", tables);
        return out;
    }

    // ---- file ----------------------------------------------------------------------------

    private static ObjectMapper yaml(boolean plain) {
        YAMLFactory factory = new YAMLFactory()
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                .disable(YAMLGenerator.Feature.SPLIT_LINES);
        if (plain) {
            factory.enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
                    .enable(YAMLGenerator.Feature.ALWAYS_QUOTE_NUMBERS_AS_STRINGS)
                    .enable(YAMLGenerator.Feature.INDENT_ARRAYS_WITH_INDICATOR);
        }
        return new ObjectMapper(factory);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> read(File file) throws IOException {
        if (!file.isFile()) return null;
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        return yaml(false).readValue(text, LinkedHashMap.class);
    }

    static void write(File file, Map<String, Object> sidecar) throws IOException {
        // Written with as few quotes as the content allows, and read back: a value that does not
        // come back as it went in - a string YAML reads as something else - gets the quoted form.
        String text = yaml(true).writeValueAsString(sidecar);
        Object back;
        try {
            back = yaml(false).readValue(text, LinkedHashMap.class);
        } catch (IOException e) {
            back = null;
        }
        if (!same(sidecar, back)) text = yaml(false).writeValueAsString(sidecar);
        text = "# What the ChartDB diagram says that the blueprint does not - positions, colours, areas,\n"
                + "# notes, and database details the DSL has no word for. Written by elasticore-chartdb\n"
                + "# (to-yaml) as the difference between the diagram and the blueprint; read by to-json.\n"
                + "# Edit the model in model/*.yml, the picture in ChartDB.\n" + text;
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        Writer w = new OutputStreamWriter(Files.newOutputStream(file.toPath()), StandardCharsets.UTF_8);
        try {
            w.write(text.replace("\r\n", "\n"));
        } finally {
            w.close();
        }
    }
}
