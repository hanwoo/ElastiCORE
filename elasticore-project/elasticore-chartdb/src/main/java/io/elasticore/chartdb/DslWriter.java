package io.elasticore.chartdb;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A blueprint for a diagram: env.yml and model/*.yml, as text.
 *
 * Written the way the DSL guide asks a person to write it - one line a field,
 * the common things as marks beside the type, a comment after {@code --} -
 * so what comes out is a model to keep working on, not an export to keep
 * regenerating:
 * <pre>
 * table                      entity  (@table, @schema where the names do not say it)
 * view                       entity @immutable
 * column                     field: type, (n), (p.s), ! for NOT NULL, @id, @sequence, @unique, @default
 * column type the DSL lacks  the nearest DSL type and @columnDef('...')
 * foreign key                a reference to the entity (@OneToOne, @col), or @fk(Entity.field) on a key column
 * many-to-many line          List&lt;Entity&gt; @ManyToMany
 * index                      @Index(name, columnList, unique)
 * check constraint           @check("..."), and @min/@max where it is a range
 * enum type                  enumeration
 * composite type             entity @embeddable, used with @Embedded
 * comment                    -- comment
 * </pre>
 * What has no line here goes to the sidecar ({@link Sidecar}); the writer
 * does not decide that - the difference with what {@link DslReader} reads
 * back does.
 */
final class DslWriter {

    static final class Options {
        String domain = "erd";
        String basePackage;
        /** Page size of {@code @expose(n)} on every table, or null to leave the API to the developer. */
        Integer expose;
    }

    /** relative path -> content */
    final Map<String, String> files = new LinkedHashMap<String, String>();
    /** custom type name in the diagram -> the enumeration or embeddable written for it */
    final Map<String, String> typeNames = new LinkedHashMap<String, String>();
    /** Decisions worth telling: a key added to a view, a type kept as text. */
    final List<String> notes = new ArrayList<String>();

    private final Map<String, Object> diagram;
    private final Options options;
    private final String database;

    private final List<TableInfo> tables = new ArrayList<TableInfo>();
    private final Map<String, TableInfo> byKey = new HashMap<String, TableInfo>();
    private final Map<String, TypeInfo> typesByName = new LinkedHashMap<String, TypeInfo>();
    private final Set<String> usedNames = new HashSet<String>();

    private static final class TableInfo {
        Map<String, Object> t;
        String key;
        String entity;
        final List<FieldInfo> fields = new ArrayList<FieldInfo>();
        final Map<String, FieldInfo> byColumn = new HashMap<String, FieldInfo>();
        final List<FieldInfo> keys = new ArrayList<FieldInfo>();
        /** {@code List<Target> @ManyToMany} lines, after the columns. */
        final List<String[]> lists = new ArrayList<String[]>();
    }

    private static final class FieldInfo {
        Map<String, Object> f;
        String column;
        String name;
        boolean key;
        /** The table this column refers to, or null. */
        TableInfo target;
        FieldInfo targetField;
        boolean one;
        /** A reference to the entity rather than {@code @fk} on the value. */
        boolean reference;
    }

    private static final class TypeInfo {
        Map<String, Object> type;
        String name;
        String dslName;
        boolean isEnum;
        final Map<String, String> constants = new LinkedHashMap<String, String>();      // value -> constant
    }

    DslWriter(Map<String, Object> namedDiagram, Options options) {
        this.diagram = namedDiagram;
        this.options = options;
        this.database = String.valueOf(namedDiagram.get("databaseType"));
    }

    void write() {
        collectTables();
        collectTypes();
        collectRelationships();
        nameFields();
        files.put(options.domain + "/env.yml", env());
        entityFiles();
        typeFiles();
    }

    // ---- what the diagram has ---------------------------------------------------------

    private void collectTables() {
        List<Map<String, Object>> list = ChartDbIo.maps(diagram.get("tables"));
        List<String> keys = Sidecar.keys(list, Sidecar.TABLE);
        for (int i = 0; i < list.size(); i++) {
            TableInfo info = new TableInfo();
            info.t = list.get(i);
            info.key = keys.get(i);
            if (byKey.containsKey(Names.key(info.t.get("schema"), info.t.get("name")))) {
                notes.add("table " + info.key + " is in the diagram twice; the second one is kept in "
                        + Sidecar.FILE_NAME + " only");
                continue;
            }
            String name = Names.type(String.valueOf(info.t.get("name")));
            if (usedNames.contains(name.toLowerCase()) && info.t.get("schema") != null) {
                name = Names.pascal(String.valueOf(info.t.get("schema"))) + name;
            }
            info.entity = unique(name);
            for (Map<String, Object> f : ChartDbIo.maps(info.t.get("fields"))) {
                FieldInfo fi = new FieldInfo();
                fi.f = f;
                fi.column = String.valueOf(f.get("name"));
                fi.key = Boolean.TRUE.equals(f.get("primaryKey"));
                if (info.byColumn.containsKey(fi.column)) continue;       // a column is in a table once
                info.fields.add(fi);
                info.byColumn.put(fi.column, fi);
                if (fi.key) info.keys.add(fi);
            }
            tables.add(info);
            byKey.put(info.key, info);
        }
    }

    private String unique(String name) {
        String candidate = name;
        for (int n = 2; usedNames.contains(candidate.toLowerCase()); n++) candidate = name + n;
        usedNames.add(candidate.toLowerCase());
        return candidate;
    }

    private void collectTypes() {
        for (Map<String, Object> t : ChartDbIo.maps(diagram.get("customTypes"))) {
            String name = String.valueOf(t.get("name"));
            if (typesByName.containsKey(name.toLowerCase())) continue;
            TypeInfo info = new TypeInfo();
            info.type = t;
            info.name = name;
            info.isEnum = "enum".equals(t.get("kind"));
            String dsl = Names.pascal(name);
            if (usedNames.contains(dsl.toLowerCase())) dsl = dsl + (info.isEnum ? "Type" : "Value");
            info.dslName = unique(dsl);
            if (info.isEnum) {
                Set<String> taken = new HashSet<String>();
                for (Object v : ChartDbIo.list(t.get("values"))) {
                    String value = String.valueOf(v);
                    String constant = Names.isConstant(value) ? value : Names.constant(value);
                    String base = constant;
                    for (int n = 2; taken.contains(constant); n++) constant = base + "_" + n;
                    taken.add(constant);
                    info.constants.put(value, constant);
                }
            }
            typesByName.put(name.toLowerCase(), info);
            typeNames.put(name, info.dslName);
        }
    }

    private TypeInfo customType(Map<String, Object> field) {
        Map<String, Object> type = ChartDbIo.map(field.get("type"));
        String id = String.valueOf(type.get("id")).toLowerCase();
        TypeInfo info = typesByName.get(id);
        if (info == null) info = typesByName.get(String.valueOf(type.get("name")).toLowerCase());
        if (info == null && id.indexOf('.') >= 0) info = typesByName.get(id.substring(id.lastIndexOf('.') + 1));
        return info;
    }

    /** Each relationship, on the column that holds the foreign key. */
    private void collectRelationships() {
        for (Map<String, Object> r : ChartDbIo.maps(diagram.get("relationships"))) {
            TableInfo source = byKey.get(String.valueOf(r.get("sourceTable")));
            TableInfo target = byKey.get(String.valueOf(r.get("targetTable")));
            if (source == null || target == null) continue;
            FieldInfo sf = source.byColumn.get(String.valueOf(r.get("sourceField")));
            FieldInfo tf = target.byColumn.get(String.valueOf(r.get("targetField")));
            if (sf == null || tf == null) continue;
            String sc = String.valueOf(r.get("sourceCardinality")), tc = String.valueOf(r.get("targetCardinality"));

            if ("many".equals(sc) && "many".equals(tc)) {
                if (source.keys.size() == 1 && source.keys.get(0) == sf && target.keys.size() == 1
                        && target.keys.get(0) == tf) {
                    source.lists.add(new String[]{Names.field(String.valueOf(target.t.get("name"))), target.entity});
                }
                continue;
            }
            // The many side holds the key; of two ones, the side that is not its own table's key.
            boolean swap = "one".equals(sc) && "many".equals(tc);
            TableInfo holder = swap ? target : source, referred = swap ? source : target;
            FieldInfo fk = swap ? tf : sf, to = swap ? sf : tf;
            if (fk.target != null) continue;       // one column, one reference; the others stay in the sidecar
            fk.target = referred;
            fk.targetField = to;
            fk.one = "one".equals(sc) && "one".equals(tc);
            boolean soleKey = referred.keys.size() == 1 && referred.keys.get(0) == to;
            fk.reference = soleKey && !fk.key && customType(fk.f) == null;
        }
    }

    private void nameFields() {
        for (TableInfo t : tables) {
            Set<String> taken = new HashSet<String>();
            // Value fields first: a reference is named after its column without the key's name, and
            // must not take a name a column of the table has.
            for (FieldInfo f : t.fields) {
                if (f.reference) continue;
                f.name = take(taken, Names.field(f.column));
            }
            for (FieldInfo f : t.fields) {
                if (!f.reference) continue;
                String base = f.column;
                String keyColumn = f.targetField.column;
                if (base.toLowerCase().endsWith("_" + keyColumn.toLowerCase()) && base.length() > keyColumn.length() + 1) {
                    base = base.substring(0, base.length() - keyColumn.length() - 1);
                } else if (base.toLowerCase().endsWith("_id") && base.length() > 3) {
                    base = base.substring(0, base.length() - 3);
                }
                String name = Names.field(base);
                if (taken.contains(name) || name.equals(Names.field(f.column)) && hasIdNamed(t, name, f)) name = name + "Ref";
                f.name = take(taken, name);
            }
            for (String[] list : t.lists) list[0] = take(taken, list[0]);
        }
    }

    /** Whether the table has a value field the generator would take for this reference's id (item + itemId). */
    private static boolean hasIdNamed(TableInfo t, String refName, FieldInfo ref) {
        String key = Names.field(ref.targetField.column);
        String idName = key.startsWith(refName) ? key : refName + Character.toUpperCase(key.charAt(0)) + key.substring(1);
        for (FieldInfo other : t.fields) {
            if (other != ref && !other.reference && idName.equals(other.name)) return true;
        }
        return false;
    }

    private static String take(Set<String> taken, String name) {
        String candidate = name;
        for (int n = 2; taken.contains(candidate); n++) candidate = name + n;
        taken.add(candidate);
        return candidate;
    }

    // ---- env.yml ---------------------------------------------------------------------------

    private String env() {
        String pkg = options.basePackage != null ? options.basePackage : "com.example." + options.domain;
        return "# " + oneLine(String.valueOf(diagram.get("name"))) + " - written from a ChartDB diagram by elasticore-chartdb.\n"
                + "elasticore:\n"
                + "  enable: true\n"
                + "  config:\n"
                + "    domainName: " + options.domain + "\n"
                + "    framework: springboot\n"
                + "    mode: jpa\n"
                + "    j2ee: jakarta\n"
                + "    apiPath: \"/api\"             # REST: /api/" + options.domain + "/...\n"
                + "  namespace:\n"
                + "    base: " + pkg + "\n";
    }

    // ---- entities ---------------------------------------------------------------------------

    private void entityFiles() {
        // One file per schema, as the guide's "a file per kind of model" reads for a diagram with schemas.
        Map<String, List<TableInfo>> bySchema = new LinkedHashMap<String, List<TableInfo>>();
        for (TableInfo t : tables) {
            Object schema = t.t.get("schema");
            String file = schema == null || String.valueOf(schema).trim().isEmpty() ? "entity" : fileName(String.valueOf(schema));
            List<TableInfo> list = bySchema.get(file);
            if (list == null) bySchema.put(file, list = new ArrayList<TableInfo>());
            list.add(t);
        }
        for (Map.Entry<String, List<TableInfo>> e : bySchema.entrySet()) {
            StringBuilder b = new StringBuilder();
            b.append("# ").append("entity".equals(e.getKey()) ? "Tables" : "Schema " + e.getKey())
                    .append(" of the ChartDB diagram \"").append(oneLine(String.valueOf(diagram.get("name")))).append("\".\n");
            b.append("entity:\n");
            for (TableInfo t : e.getValue()) entity(b, t);
            files.put(options.domain + "/model/" + e.getKey() + ".yml", b.toString());
        }
    }

    private static String fileName(String schema) {
        String name = schema.trim().toLowerCase().replaceAll("[^a-z0-9_-]+", "_");
        // enum.yml and value.yml are the two files this writer keeps for the custom types.
        if (name.isEmpty() || "enum".equals(name) || "value".equals(name) || "env".equals(name)) name = "schema_" + name;
        return name;
    }

    private void entity(StringBuilder b, TableInfo t) {
        boolean view = Boolean.TRUE.equals(t.t.get("isView")) || Boolean.TRUE.equals(t.t.get("isMaterializedView"));
        String table = String.valueOf(t.t.get("name"));

        List<String> more = new ArrayList<String>();
        StringBuilder meta = new StringBuilder("entity");
        if (view) meta.append(" @immutable");
        if (options.expose != null && !view) meta.append(" @expose(").append(options.expose).append(')');
        if (!Names.snake(t.entity).equals(table)) meta.append(" @table(").append(table).append(')');
        Object schema = t.t.get("schema");
        if (schema != null && !String.valueOf(schema).trim().isEmpty()) meta.append(" @schema(").append(schema).append(')');
        for (Map<String, Object> ix : ChartDbIo.maps(t.t.get("indexes"))) {
            if (Boolean.TRUE.equals(ix.get("isPrimaryKey"))) continue;
            List<Object> columns = ChartDbIo.list(ix.get("fields"));
            String name = ix.get("name") == null ? "" : String.valueOf(ix.get("name"));
            if (columns.isEmpty() || !plainName(name) && !name.isEmpty()) continue;
            StringBuilder list = new StringBuilder();
            boolean plain = true;
            for (Object c : columns) {
                plain &= plainName(String.valueOf(c));
                list.append(list.length() > 0 ? "," : "").append(c);
            }
            if (!plain) continue;
            StringBuilder index = new StringBuilder("@Index(");
            if (!name.isEmpty()) index.append("name=\"").append(name).append("\", ");
            index.append("columnList=\"").append(list).append('"');
            if (Boolean.TRUE.equals(ix.get("unique"))) index.append(", unique=\"true\"");
            more.add(index.append(')').toString());
        }
        for (Object check : ChartDbIo.list(t.t.get("checkConstraints"))) {
            if (check != null && quotable(String.valueOf(check))) more.add("@check(\"" + check + "\")");
        }
        String comment = comment(t.t.get("comments"));

        b.append('\n');
        b.append("  ").append(t.entity).append(":\n");
        StringBuilder oneLine = new StringBuilder(meta);
        for (String m : more) oneLine.append(' ').append(m);
        oneLine.append(comment);
        String whole = scalar(oneLine.toString());
        if (whole.length() <= 110 || whole.startsWith("\"")) {
            b.append("    meta: ").append(whole).append('\n');
        } else {
            // YAML reads the continuation lines of a plain value as one line, a space between them.
            b.append("    meta: ").append(meta);
            for (int i = 0; i < more.size(); i++) {
                b.append("\n          ").append(more.get(i));
            }
            b.append(comment).append('\n');
        }
        b.append("    fields:\n");

        boolean syntheticKey = t.keys.isEmpty() && !t.fields.isEmpty();
        if (syntheticKey) {
            notes.add((view ? "view " : "table ") + t.key + " has no primary key; a JPA entity needs one, so "
                    + t.fields.get(0).column + " is marked @id (the diagram keeps it as it was)");
        }
        int width = 0;
        for (FieldInfo f : t.fields) width = Math.max(width, f.name.length());
        for (String[] list : t.lists) width = Math.max(width, list[0].length());
        boolean first = true;
        for (FieldInfo f : t.fields) {
            String line = fieldLine(t, f, syntheticKey && first);
            first = false;
            b.append("      ").append(pad(f.name + ":", width + 1)).append(' ').append(scalar(line)).append('\n');
        }
        for (String[] list : t.lists) {
            b.append("      ").append(pad(list[0] + ":", width + 1)).append(" List<").append(list[1]).append("> @ManyToMany\n");
        }
    }

    private static boolean plainName(String name) {
        return name.matches("[A-Za-z0-9_$.\\-]+");
    }

    private String fieldLine(TableInfo t, FieldInfo fi, boolean syntheticKey) {
        Map<String, Object> f = fi.f;
        boolean key = fi.key || syntheticKey;
        boolean required = Boolean.FALSE.equals(f.get("nullable")) && !key;
        StringBuilder line = new StringBuilder();

        if (fi.reference) {
            line.append(fi.target.entity);
            if (required) line.append('!');
            if (fi.one) line.append(" @OneToOne");
            if (!(Names.snake(fi.name) + "_" + fi.targetField.column).equals(fi.column)) {
                line.append(" @col(").append(fi.column).append(')');
            }
            if (Boolean.TRUE.equals(f.get("unique")) && !fi.one) line.append(" @unique");
            return line.append(comment(f.get("comments"))).toString();
        }

        TypeInfo custom = customType(f);
        String dsl = null;
        if (custom != null && !custom.isEnum) {
            line.append(custom.dslName);
            if (required) line.append('!');
            line.append(Names.snake(fi.name).equals(fi.column) ? " @Embedded" : " @Embedded(" + fi.column + ")");
            return line.append(comment(f.get("comments"))).toString();
        }
        if (custom != null) {
            line.append(custom.dslName);
            if (required) line.append('!');
        } else {
            Map<String, Object> type = ChartDbIo.map(f.get("type"));
            SqlTypes.Column c = new SqlTypes.Column();
            c.id = String.valueOf(type.get("id"));
            c.name = type.get("name") == null ? c.id : String.valueOf(type.get("name"));
            c.length = f.get("characterMaximumLength") == null ? null : String.valueOf(f.get("characterMaximumLength"));
            c.precision = f.get("precision") instanceof Number ? ((Number) f.get("precision")).intValue() : null;
            c.scale = f.get("scale") instanceof Number ? ((Number) f.get("scale")).intValue() : null;
            c.array = Boolean.TRUE.equals(f.get("isArray"));
            String[] typed = dslType(c, Boolean.TRUE.equals(f.get("increment")), t.key + "." + fi.column);
            dsl = typed[2];
            line.append(typed[0]);
            if (required) line.append('!');
            line.append(typed[1]);
        }
        if (key) line.append(" @id");
        if (Boolean.TRUE.equals(f.get("increment"))) line.append(" @sequence");
        String def = f.get("default") == null ? null : String.valueOf(f.get("default")).trim();
        if (fi.key && def != null && SqlTypes.STRING.equals(dsl) && def.toLowerCase().matches("(gen_random_uuid|uuid_generate_v4|newid|uuid)\\(\\)")) {
            line.append(" @genid");
        }
        if (!Names.snake(fi.name).equals(fi.column)) line.append(" @Column(name=\"").append(fi.column).append("\")");
        if (Boolean.TRUE.equals(f.get("unique")) && !key) line.append(" @unique");
        String defaultAnnotation = defaultAnnotation(def, dsl, custom);
        if (defaultAnnotation != null) line.append(' ').append(defaultAnnotation);
        if (fi.target != null && fi.targetField.name != null && !fi.targetField.reference) {
            line.append(" @fk(").append(fi.target.entity).append('.').append(fi.targetField.name).append(')');
        }
        String check = f.get("check") == null ? null : String.valueOf(f.get("check")).trim();
        if (check != null && !check.isEmpty()) {
            line.append(range(check, fi.column, dsl));
            if (quotable(check)) line.append(" @check(\"").append(check).append("\")");
        }
        return line.append(comment(f.get("comments"))).toString();
    }

    /**
     * The DSL type of a column type: the type text, the annotations it needs, and the DSL type word.
     *
     * {@code @columnDef} is added where the DSL type alone would not give the column type back.
     */
    private String[] dslType(SqlTypes.Column c, boolean increment, String where) {
        String id = SqlTypes.normalize(c.id);
        String dsl = SqlTypes.dslOf(database, id);
        boolean numericLength = c.length != null && c.length.matches("\\d+");
        String text;
        if (c.array) {
            text = SqlTypes.INT.equals(dsl) ? "int[]" : "string[]";
        } else if (SqlTypes.STRING.equals(dsl)) {
            text = numericLength ? "string(" + c.length + ")" : "string";
        } else if (SqlTypes.DECIMAL.equals(dsl)) {
            text = c.precision != null ? "decimal(" + c.precision + "." + (c.scale == null ? 0 : c.scale) + ")" : "decimal";
        } else {
            text = dsl;
        }

        // What the reader derives from that text, and whether it is this column.
        String canonical = SqlTypes.canonical(database, c.array && !SqlTypes.INT.equals(dsl) ? SqlTypes.STRING : dsl);
        boolean serial = increment && ("serial".equals(id) && SqlTypes.INT.equals(dsl)
                || "bigserial".equals(id) && SqlTypes.LONG.equals(dsl));
        boolean same = (canonical.equals(id) || serial)
                && (SqlTypes.STRING.equals(dsl) && !c.array ? c.length == null || numericLength : c.length == null)
                && (SqlTypes.DECIMAL.equals(dsl) && !c.array ? c.precision == null || c.scale != null : c.precision == null);
        String annotations = "";
        if (!same) {
            String sql = c.sql();
            if (sql.indexOf('\'') < 0 && sql.indexOf('"') < 0) annotations = " @columnDef('" + sql + "')";
            if (!SqlTypes.known(id)) {
                notes.add(where + ": " + sql + " is not a type the DSL has; kept as a string with @columnDef");
            }
        }
        return new String[]{text, annotations, c.array ? null : dsl};
    }

    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");
    private static final Pattern QUOTED = Pattern.compile("'([^'\"()@\\\\\\r\\n]*)'(?:::[\\w .\"]+)?");

    /** {@code @default(...)} for a DEFAULT clause the DSL can say; null for one it cannot. */
    private String defaultAnnotation(String def, String dsl, TypeInfo custom) {
        if (def == null || def.isEmpty()) return null;
        if (custom != null) {
            Matcher m = QUOTED.matcher(def);
            String value = m.matches() ? m.group(1) : def;
            String constant = custom.constants.get(value);
            return constant == null ? null : "@default(" + custom.dslName + "." + constant + ")";
        }
        if (dsl == null) return null;
        if (SqlTypes.INT.equals(dsl) || SqlTypes.LONG.equals(dsl) || SqlTypes.BIGINTEGER.equals(dsl)) {
            return def.matches("-?\\d+") ? "@default(" + def + ")" : null;
        }
        if (SqlTypes.DECIMAL.equals(dsl) || SqlTypes.FLOAT.equals(dsl) || SqlTypes.DOUBLE.equals(dsl)) {
            return NUMBER.matcher(def).matches() ? "@default(" + def + ")" : null;
        }
        if (SqlTypes.BOOLEAN.equals(dsl)) {
            return def.equals("true") || def.equals("false") ? "@default(" + def + ")" : null;
        }
        if (SqlTypes.DATE.equals(dsl) || SqlTypes.TIME.equals(dsl) || SqlTypes.DATETIME.equals(dsl)
                || SqlTypes.TIMESTAMP.equals(dsl)) {
            String d = def.toLowerCase().replace(" ", "");
            return d.equals("now()") || d.equals("current_timestamp") || d.equals("current_timestamp()")
                    || d.equals("current_date") || d.equals("current_time") || d.equals("getdate()")
                    || d.equals("sysdate") ? "@default(now)" : null;
        }
        if (SqlTypes.STRING.equals(dsl) || SqlTypes.TEXT.equals(dsl)) {
            Matcher m = QUOTED.matcher(def);
            // The DSL quotes the value itself; only text with nothing its parser reads goes there.
            return m.matches() && !m.group(1).isEmpty() && def.endsWith("'") && m.group(1).indexOf(" --") < 0
                    && m.group(1).indexOf('{') < 0 ? "@default('" + m.group(1) + "')" : null;
        }
        return null;
    }

    /** {@code @min}/{@code @max} for a check that is a range of whole numbers on this column. */
    private static String range(String check, String column, String dsl) {
        boolean whole = SqlTypes.INT.equals(dsl) || SqlTypes.LONG.equals(dsl);
        if (!whole && !SqlTypes.DECIMAL.equals(dsl)) return "";
        String col = "\"?" + Pattern.quote(column) + "\"?";
        String n = "(-?\\d+)";
        String s = check.trim();
        Matcher m;
        if ((m = Pattern.compile("^" + col + "\\s+BETWEEN\\s+" + n + "\\s+AND\\s+" + n + "$", Pattern.CASE_INSENSITIVE).matcher(s)).matches()
                || (m = Pattern.compile("^" + col + "\\s*>=\\s*" + n + "\\s+AND\\s+" + col + "\\s*<=\\s*" + n + "$", Pattern.CASE_INSENSITIVE).matcher(s)).matches()) {
            return " @min(" + m.group(1) + ") @max(" + m.group(2) + ")";
        }
        if ((m = Pattern.compile("^" + col + "\\s*>=\\s*" + n + "$").matcher(s)).matches()) return " @min(" + m.group(1) + ")";
        if ((m = Pattern.compile("^" + col + "\\s*<=\\s*" + n + "$").matcher(s)).matches()) return " @max(" + m.group(1) + ")";
        if (whole && (m = Pattern.compile("^" + col + "\\s*>\\s*" + n + "$").matcher(s)).matches()) {
            return " @min(" + (Long.parseLong(m.group(1)) + 1) + ")";
        }
        if (whole && (m = Pattern.compile("^" + col + "\\s*<\\s*" + n + "$").matcher(s)).matches()) {
            return " @max(" + (Long.parseLong(m.group(1)) - 1) + ")";
        }
        return "";
    }

    // ---- enumerations and embeddables ------------------------------------------------------

    private void typeFiles() {
        StringBuilder enums = new StringBuilder();
        StringBuilder values = new StringBuilder();
        for (TypeInfo info : typesByName.values()) {
            if (info.isEnum) {
                enumeration(enums, info);
            } else {
                embeddable(values, info);
            }
        }
        String of = " of the ChartDB diagram \"" + oneLine(String.valueOf(diagram.get("name"))) + "\".\n";
        if (enums.length() > 0) {
            files.put(options.domain + "/model/enum.yml", "# Enum types" + of + "enumeration:\n" + enums);
        }
        if (values.length() > 0) {
            files.put(options.domain + "/model/value.yml", "# Composite types" + of + "entity:\n" + values);
        }
    }

    private void enumeration(StringBuilder b, TypeInfo info) {
        int longest = 1, width = 0;
        for (Map.Entry<String, String> e : info.constants.entrySet()) {
            longest = Math.max(longest, e.getKey().length());
            width = Math.max(width, key(e.getValue()).length());
        }
        // Every constant carries the value the column holds as its code, also where the two are the
        // same word: the value may not be a name Java allows ('in-transit'), and the generator
        // needs an enumeration to have a field (one without stops it with a NullPointerException).
        b.append('\n');
        b.append("  ").append(info.dslName).append(":\n");
        b.append("    meta: enum @db(code) @json(code)\n");
        b.append("    fields:\n");
        b.append("      code: string(").append(longest).append(") @code\n");
        b.append("    enum:\n");
        for (Map.Entry<String, String> e : info.constants.entrySet()) {
            // Quoted: YAML reads a bare ON, NO or 1 as something other than the text it is.
            b.append("      ").append(pad(key(e.getValue()) + ":", width + 1)).append(' ').append(quoted(e.getKey())).append('\n');
        }
    }

    private void embeddable(StringBuilder b, TypeInfo info) {
        b.append('\n');
        b.append("  ").append(info.dslName).append(":\n");
        b.append("    meta: entity @embeddable\n");
        b.append("    fields:\n");
        List<Map<String, Object>> fields = ChartDbIo.maps(info.type.get("fields"));
        Set<String> taken = new HashSet<String>();
        List<String> names = new ArrayList<String>();
        int width = 0;
        for (Map<String, Object> f : fields) {
            String name = take(taken, Names.field(String.valueOf(f.get("field"))));
            names.add(name);
            width = Math.max(width, name.length());
        }
        for (int i = 0; i < fields.size(); i++) {
            Map<String, Object> f = fields.get(i);
            String column = String.valueOf(f.get("field"));
            String sql = String.valueOf(f.get("type"));
            StringBuilder line = new StringBuilder();
            TypeInfo nested = typesByName.get(sql.toLowerCase());
            if (nested != null && nested.isEnum) {
                line.append(nested.dslName);
            } else {
                String[] typed = dslType(SqlTypes.parse(sql), false, info.name + "." + column);
                line.append(typed[0]).append(typed[1]);
            }
            if (!Names.snake(names.get(i)).equals(column)) line.append(" @Column(name=\"").append(column).append("\")");
            b.append("      ").append(pad(names.get(i) + ":", width + 1)).append(' ').append(scalar(line.toString())).append('\n');
        }
    }

    // ---- text ------------------------------------------------------------------------------

    /**
     * {@code -- comment}, as far as a DSL line can hold it.
     *
     * The loader turns the comment into {@code @label(comment)}: an {@code @} would start an
     * annotation, an {@code =} a key and its value, parentheses must pair and not nest, and a line
     * is one line. A comment with {@code =} or such parentheses is written in quotes, which the
     * loader reads whole; one that still does not fit is written in a form that does, and the
     * sidecar keeps the original.
     */
    static String comment(Object comments) {
        if (comments == null) return "";
        String c = String.valueOf(comments).replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
        if (c.isEmpty()) return "";
        c = c.replace("@", "(at)").replace(" --", " -");
        if (c.indexOf('=') >= 0 || !parenthesesFit(c)) {
            // One quoted value is read whole, whatever it holds - as @formula("...") is.
            if (c.indexOf('"') < 0 && c.indexOf(" | ") < 0) return " -- \"" + c + "\"";
            c = c.replace('=', ':');
            if (!parenthesesFit(c)) c = c.replace('(', '[').replace(')', ']');
        }
        return " -- " + c;
    }

    private static boolean parenthesesFit(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '(' && ++depth > 1) return false;
            if (ch == ')' && --depth < 0) return false;
        }
        return depth == 0;
    }

    /** Whether an expression can stand in {@code @check("...")}. */
    private static boolean quotable(String expression) {
        return expression.indexOf('"') < 0 && expression.indexOf('\n') < 0 && expression.indexOf('\r') < 0
                && expression.indexOf(" --") < 0 && !expression.trim().isEmpty();
    }

    private static String oneLine(String s) {
        return s.replace('\n', ' ').replace('\r', ' ').replace("\"", "'");
    }

    private static String pad(String s, int width) {
        StringBuilder b = new StringBuilder(s);
        while (b.length() < width) b.append(' ');
        return b.toString();
    }

    /** A YAML mapping key: quoted where YAML would read the word as something other than a string. */
    private static String key(String name) {
        return name.matches("(?i)y|n|yes|no|on|off|true|false|null|~") || !name.matches("[A-Za-z_][A-Za-z0-9_]*")
                ? quoted(name) : name;
    }

    /** A YAML value on one line: plain where YAML reads it back as the same string, quoted where it would not. */
    static String scalar(String value) {
        boolean plain = !value.isEmpty()
                && value.indexOf(": ") < 0 && value.indexOf(" #") < 0 && !value.endsWith(":")
                && value.indexOf('\t') < 0 && value.equals(value.trim())
                && "!&*-?|>'\"%@`#,[]{}".indexOf(value.charAt(0)) < 0;
        return plain ? value : quoted(value);
    }

    private static String quoted(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
