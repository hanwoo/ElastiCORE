package io.elasticore.chartdb;

import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;

/**
 * ChartDB diagrams and blueprints, in both directions.
 *
 * <pre>
 * to-yaml  --json=shop.chartdb.json --blueprint=src/main/resources/blueprint --domain=shop --package=com.acme.shop
 *          [--expose=20] [--force=true]
 * to-json  --blueprint=src/main/resources/blueprint --json=shop.chartdb.json [--database=postgresql] [--name=Shop]
 * verify   --json=shop.chartdb.json [--work=build/chartdb-verify]
 * </pre>
 *
 * {@code to-yaml} writes {@code <blueprint>/<domain>/env.yml}, {@code model/*.yml} and
 * {@code <blueprint>/chartdb.yml}; {@code to-json} reads a blueprint - with or without that
 * sidecar - and writes the JSON ChartDB imports. {@code verify} does both and says what the
 * round trip changed, which is nothing unless the diagram holds something neither file can.
 */
public final class ChartDbConverter {

    /** What a conversion did, for the caller to print or to assert on. */
    public static final class Result {
        /** Problems with the input: it was converted, without the part named. */
        public final List<String> warnings = new ArrayList<String>();
        /** Decisions the writer took that the reader of the result should know. */
        public final List<String> notes = new ArrayList<String>();
        /** What the generator's loader said about the DSL. Empty for a blueprint it accepts. */
        public final List<String> dslMessages = new ArrayList<String>();
        /** Facts of the diagram that are in the sidecar rather than in the DSL, by kind. */
        public final Map<String, List<String>> kept = new TreeMap<String, List<String>>();
        /** Differences between the diagram and the round trip of it. Empty when nothing was lost. */
        public final List<String> lost = new ArrayList<String>();
        public final List<String> written = new ArrayList<String>();
        public int tables, relationships, customTypes;

        void print(PrintStream out) {
            out.println("[chartdb] " + tables + " table(s), " + relationships + " relationship(s), "
                    + customTypes + " custom type(s)");
            for (String f : written) out.println("[chartdb] wrote " + f);
            for (String w : warnings) out.println("[chartdb] WARN  " + w);
            for (String n : notes) out.println("[chartdb] note  " + n);
            for (String m : dslMessages) out.println("[chartdb] DSL   " + m);
            if (!kept.isEmpty()) {
                out.println("[chartdb] in " + Sidecar.FILE_NAME + " (the DSL has no word for it):");
                for (Map.Entry<String, List<String>> e : kept.entrySet()) {
                    List<String> where = e.getValue();
                    StringBuilder b = new StringBuilder();
                    for (int i = 0; i < Math.min(3, where.size()); i++) b.append(i > 0 ? ", " : "").append(where.get(i));
                    if (where.size() > 3) b.append(", ...");
                    out.println("[chartdb]   " + e.getKey() + " x" + where.size() + "  (" + b + ")");
                }
            }
            for (String l : lost) out.println("[chartdb] LOST  " + l);
        }
    }

    private ChartDbConverter() {
    }

    public static void main(String[] args) throws IOException {
        Map<String, String> options = new LinkedHashMap<String, String>();
        String command = null;
        for (String a : args) {
            if (a.startsWith("--") && a.indexOf('=') > 0) {
                options.put(a.substring(2, a.indexOf('=')), a.substring(a.indexOf('=') + 1));
            } else if (command == null) {
                command = a;
            }
        }
        PrintStream out = new PrintStream(System.out, true, "UTF-8");
        Result result;
        if ("to-yaml".equals(command) && options.containsKey("json") && options.containsKey("blueprint")) {
            DslWriter.Options o = new DslWriter.Options();
            if (options.containsKey("domain")) o.domain = options.get("domain");
            o.basePackage = options.get("package");
            if (options.containsKey("expose")) o.expose = Integer.valueOf(options.get("expose"));
            result = toYaml(new File(options.get("json")), new File(options.get("blueprint")), o,
                    "true".equals(options.get("force")));
        } else if ("to-json".equals(command) && options.containsKey("json") && options.containsKey("blueprint")) {
            result = toJson(new File(options.get("blueprint")), new File(options.get("json")),
                    options.get("database"), options.get("name"));
        } else if ("verify".equals(command) && options.containsKey("json")) {
            result = verify(new File(options.get("json")),
                    new File(options.containsKey("work") ? options.get("work") : "build/chartdb-verify"));
            out.println(result.lost.isEmpty()
                    ? "[chartdb] round trip: the diagram came back as it went in"
                    : "[chartdb] round trip: " + result.lost.size() + " difference(s)");
        } else {
            out.println("usage: to-yaml --json=<diagram.json> --blueprint=<dir> [--domain=erd] [--package=com.acme.erd]"
                    + " [--expose=20] [--force=true]\n"
                    + "       to-json --blueprint=<dir> --json=<diagram.json> [--database=postgresql] [--name=...]\n"
                    + "       verify  --json=<diagram.json> [--work=<dir>]");
            System.exit(2);
            return;
        }
        result.print(out);
        if (!result.lost.isEmpty() || !result.dslMessages.isEmpty()) System.exit(1);
    }

    // ---- diagram -> blueprint ---------------------------------------------------------------

    /**
     * Writes the blueprint of a diagram.
     *
     * @param blueprintRoot the directory that holds one directory per domain
     * @param force         write over a domain that is already there
     */
    public static Result toYaml(File json, File blueprintRoot, DslWriter.Options options, boolean force)
            throws IOException {
        Result result = new Result();
        Map<String, Object> file = Json.parseObject(read(json));
        Map<String, Object> named = ChartDbIo.named(file, result.warnings);
        long stamp = ChartDbIo.stamp(file);
        if (!options.domain.matches("[A-Za-z][A-Za-z0-9-]*")) {
            throw new IllegalArgumentException("domain '" + options.domain + "' - letters, digits and hyphens, starting"
                    + " with a letter");
        }

        DslWriter writer = new DslWriter(named, options);
        writer.write();
        result.notes.addAll(writer.notes);

        File domainDir = new File(blueprintRoot, options.domain);
        if (new File(domainDir, "env.yml").isFile() && !force) {
            throw new IllegalStateException(domainDir + " already holds a domain. Pass --force=true to write over"
                    + " what this converter wrote there before; files of your own are left alone.");
        }
        // A model file an earlier conversion wrote for a schema the diagram no longer has.
        File[] earlier = new File(domainDir, "model").listFiles();
        if (earlier != null) {
            for (File f : earlier) {
                String relative = options.domain + "/model/" + f.getName();
                if (f.isFile() && f.getName().endsWith(".yml") && !writer.files.containsKey(relative)
                        && read(f).startsWith("# ") && read(f).split("\n", 2)[0].contains("of the ChartDB diagram")) {
                    f.delete();
                }
            }
        }
        for (Map.Entry<String, String> e : writer.files.entrySet()) {
            File target = new File(blueprintRoot, e.getKey());
            write(target, e.getValue());
            result.written.add(target.getPath());
        }

        // What the blueprint alone gives back, and beside it what it does not.
        String database = String.valueOf(named.get("databaseType"));
        DslReader reader = DslReader.read(blueprintRoot, database, result.warnings, options.domain);
        result.dslMessages.addAll(reader.dslMessages);
        Map<String, Object> sidecar = Sidecar.diff(named, reader.diagram(), writer.typeNames, stamp);
        File sidecarFile = new File(blueprintRoot, Sidecar.FILE_NAME);
        Sidecar.write(sidecarFile, sidecar);
        result.written.add(sidecarFile.getPath());
        summarize(sidecar, result);

        // Read back from the files, as to-json will: what differs now would differ then.
        Map<String, Object> back = Sidecar.apply(reader.diagram(), Sidecar.read(sidecarFile), reader.dslNames);
        Diff.of(named, back, "", result.lost);
        count(named, result);
        return result;
    }

    // ---- blueprint -> diagram ---------------------------------------------------------------

    /**
     * Writes the diagram of a blueprint.
     *
     * @param database the diagram's database where no sidecar says it (postgresql)
     * @param name     the diagram's name where no sidecar says it (the domain names)
     */
    public static Result toJson(File blueprintRoot, File json, String database, String name) throws IOException {
        Result result = new Result();
        Map<String, Object> named = named(blueprintRoot, database, name, result);
        long stamp = System.currentTimeMillis();
        Map<String, Object> sidecar = Sidecar.read(new File(blueprintRoot, Sidecar.FILE_NAME));
        if (sidecar != null && ChartDbIo.map(sidecar.get("chartdb")).get("stamp") instanceof Number) {
            stamp = ((Number) ChartDbIo.map(sidecar.get("chartdb")).get("stamp")).longValue();
        }
        write(json, Json.write(ChartDbIo.diagram(named, stamp)));
        result.written.add(json.getPath());
        count(named, result);
        return result;
    }

    /** The named diagram of a blueprint: what its DSL says, with what its sidecar keeps or a layout of its own. */
    static Map<String, Object> named(File blueprintRoot, String database, String name, Result result)
            throws IOException {
        Map<String, Object> sidecar = Sidecar.read(new File(blueprintRoot, Sidecar.FILE_NAME));
        Map<String, Object> side = sidecar == null ? null : ChartDbIo.map(sidecar.get("chartdb"));
        if (database == null && side != null && ChartDbIo.map(side.get("diagram")).get("databaseType") != null) {
            database = String.valueOf(ChartDbIo.map(side.get("diagram")).get("databaseType"));
        }
        DslReader reader = DslReader.read(blueprintRoot, database, result.warnings, null);
        result.dslMessages.addAll(reader.dslMessages);
        Map<String, Object> derived = reader.diagram();

        if (sidecar == null) {
            // No picture yet: one area a domain where there are several, else one a schema.
            Map<String, List<String>> groups = reader.tablesByDomain;
            if (groups.size() < 2) {
                Map<String, List<String>> bySchema = new LinkedHashMap<String, List<String>>();
                for (Map<String, Object> t : ChartDbIo.maps(derived.get("tables"))) {
                    String schema = t.get("schema") == null ? "" : String.valueOf(t.get("schema"));
                    List<String> keys = bySchema.get(schema);
                    if (keys == null) bySchema.put(schema, keys = new ArrayList<String>());
                    keys.add(Names.key(t.get("schema"), t.get("name")));
                }
                if (bySchema.size() > 1) groups = bySchema;
            }
            Layout.arrange(derived, groups);
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("id", "0");
            out.putAll(derived);
            if (name != null) out.put("name", name);
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
            iso.setTimeZone(TimeZone.getTimeZone("UTC"));
            String now = iso.format(new Date());
            out.put("createdAt", now);
            out.put("updatedAt", now);
            return out;
        }

        Map<String, Object> named = Sidecar.apply(derived, sidecar, reader.dslNames);
        if (name != null) named.put("name", name);
        // A table added to the blueprint since the diagram was converted has no place in the sidecar.
        List<String> placed = new ArrayList<String>();
        for (Map.Entry<String, Object> e : ChartDbIo.map(side.get("tables")).entrySet()) {
            if (e.getValue() instanceof Map && ChartDbIo.map(e.getValue()).containsKey("x")) placed.add(e.getKey());
        }
        List<String> keys = Sidecar.keys(ChartDbIo.maps(named.get("tables")), Sidecar.TABLE);
        if (!placed.containsAll(keys)) {
            Layout.placeNew(named, placed);
            for (String k : keys) {
                if (!placed.contains(k)) result.notes.add("table " + k + " is not in " + Sidecar.FILE_NAME
                        + " - new in the blueprint - and is placed under the diagram");
            }
        }
        return named;
    }

    // ---- there and back ----------------------------------------------------------------------

    /** Converts a diagram to a blueprint and back in {@code work}, and lists what came back different. */
    public static Result verify(File json, File work) throws IOException {
        File blueprint = new File(work, "blueprint");
        deleteTree(blueprint);
        DslWriter.Options options = new DslWriter.Options();
        options.domain = "verify";
        Result result = toYaml(json, blueprint, options, true);
        File back = new File(work, json.getName());
        Result second = toJson(blueprint, back, null, null);
        result.written.addAll(second.written);
        result.dslMessages.addAll(second.dslMessages);

        List<String> ignored = new ArrayList<String>();
        Map<String, Object> before = ChartDbIo.named(Json.parseObject(read(json)), ignored);
        Map<String, Object> after = ChartDbIo.named(Json.parseObject(read(back)), ignored);
        result.lost.clear();
        Diff.of(before, after, "", result.lost);
        if (result.lost.isEmpty() && read(json).trim().equals(read(back).trim())) {
            result.notes.add("the file written back is the file read, character for character");
        }
        return result;
    }

    // ---- report -------------------------------------------------------------------------------

    private static void count(Map<String, Object> named, Result result) {
        result.tables = ChartDbIo.list(named.get("tables")).size();
        result.relationships = ChartDbIo.list(named.get("relationships")).size();
        result.customTypes = ChartDbIo.list(named.get("customTypes")).size();
    }

    /** What the sidecar holds besides the picture - the facts a reader of the DSL alone would not see. */
    private static void summarize(Map<String, Object> sidecar, Result result) {
        Map<String, Object> side = ChartDbIo.map(sidecar.get("chartdb"));
        for (Map.Entry<String, Object> t : ChartDbIo.map(side.get("tables")).entrySet()) {
            if (t.getKey().startsWith("$")) {
                keep(result, "table " + t.getKey(), ChartDbIo.list(t.getValue()).size() + " table(s)");
                continue;
            }
            Map<String, Object> item = ChartDbIo.map(t.getValue());
            if (Boolean.TRUE.equals(item.get("$new"))) {
                keep(result, "table the DSL does not hold", t.getKey());
                continue;
            }
            for (Map.Entry<String, Object> a : item.entrySet()) {
                String k = a.getKey();
                if (Sidecar.LAYOUT.contains(k)) continue;
                if ("fields".equals(k) || "indexes".equals(k)) {
                    String kind = "fields".equals(k) ? "field" : "index";
                    for (Map.Entry<String, Object> f : ChartDbIo.map(a.getValue()).entrySet()) {
                        String where = t.getKey() + "." + f.getKey();
                        if (f.getKey().startsWith("$")) {
                            keep(result, kind + " " + f.getKey(), t.getKey());
                        } else if (Boolean.TRUE.equals(ChartDbIo.map(f.getValue()).get("$new"))) {
                            keep(result, kind + " the DSL does not hold", where);
                        } else {
                            for (String attribute : ChartDbIo.map(f.getValue()).keySet()) {
                                keep(result, kind + " " + ("$unset".equals(attribute)
                                        ? "without " + ChartDbIo.map(f.getValue()).get(attribute) : attribute), where);
                            }
                        }
                    }
                } else {
                    keep(result, "table " + ("$unset".equals(k) ? "without " + a.getValue() : k), t.getKey());
                }
            }
        }
        for (String list : new String[]{"relationships", "customTypes"}) {
            String kind = "relationships".equals(list) ? "relationship" : "custom type";
            for (Map.Entry<String, Object> r : ChartDbIo.map(side.get(list)).entrySet()) {
                if (r.getKey().startsWith("$")) {
                    keep(result, kind + " " + r.getKey(), ChartDbIo.list(r.getValue()).size() + " of them");
                } else if (Boolean.TRUE.equals(ChartDbIo.map(r.getValue()).get("$new"))) {
                    keep(result, kind + " the DSL does not hold", r.getKey());
                } else {
                    for (String attribute : ChartDbIo.map(r.getValue()).keySet()) {
                        keep(result, kind + " " + ("$unset".equals(attribute)
                                ? "without " + ChartDbIo.map(r.getValue()).get(attribute) : attribute), r.getKey());
                    }
                }
            }
        }
        for (String list : new String[]{"areas", "notes", "dependencies"}) {
            for (Object each : ChartDbIo.list(side.get(list))) {
                Map<String, Object> m = ChartDbIo.map(each);
                Object label = m.containsKey("name") ? m.get("name") : m.containsKey("dependentTable")
                        ? m.get("dependentTable") + " <- " + m.get("table") : String.valueOf(m.get("content")).split("\n")[0];
                keep(result, list, String.valueOf(label));
            }
        }
    }

    private static void keep(Result result, String kind, String where) {
        List<String> list = result.kept.get(kind);
        if (list == null) result.kept.put(kind, list = new ArrayList<String>());
        list.add(where);
    }

    // ---- files --------------------------------------------------------------------------------

    static String read(File file) throws IOException {
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    static void write(File file, String text) throws IOException {
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent != null) parent.mkdirs();
        Writer w = new OutputStreamWriter(Files.newOutputStream(file.toPath()), StandardCharsets.UTF_8);
        try {
            w.write(text);
        } finally {
            w.close();
        }
    }

    static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File c : children) deleteTree(c);
        file.delete();
    }
}
