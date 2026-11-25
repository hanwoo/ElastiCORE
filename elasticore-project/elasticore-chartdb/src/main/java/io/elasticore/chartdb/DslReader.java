package io.elasticore.chartdb;

import io.elasticore.base.ECoreModelContext;
import io.elasticore.base.model.ECoreModel;
import io.elasticore.base.model.ModelComponentItems;
import io.elasticore.base.model.core.Annotation;
import io.elasticore.base.model.entity.BaseFieldType;
import io.elasticore.base.model.entity.Entity;
import io.elasticore.base.model.entity.EntityModels;
import io.elasticore.base.model.entity.Field;
import io.elasticore.base.model.enums.EnumConstant;
import io.elasticore.base.model.enums.EnumModel;
import io.elasticore.base.model.pub.rules.verify.BlueprintLoader;
import io.elasticore.base.util.ConsoleLog;

import java.io.File;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * The diagram a blueprint describes, before anything a sidecar adds.
 *
 * The blueprint is loaded by elasticore-base's own loader - the path the
 * generator takes - and read the way {@code OssieSemanticModelExporter} reads
 * it: an entity with a table of its own is a table, a to-one reference is its
 * join column and a relationship, an enumeration and an {@code @embeddable}
 * are custom types where the database has them. What the DSL does not say -
 * where a table is drawn, its colour, a note - is not made up here beyond a
 * layout that keeps the tables apart; {@link Sidecar} holds the rest.
 *
 * The result is a named diagram ({@link ChartDbIo}). {@link #dslNames} tells
 * which entity or enumeration each table and custom type came from.
 */
final class DslReader {

    static final String DEFAULT_TABLE_COLOR = "#8eb7ff";
    static final String VIEW_COLOR = "#b0b0b0";

    private final String database;
    private final List<String> warnings;
    private final boolean customTypes;

    private final List<Unit> units = new ArrayList<Unit>();
    private final Map<Entity, Unit> unitOf = new IdentityHashMap<Entity, Unit>();
    private final List<Map<String, Object>> relationships = new ArrayList<Map<String, Object>>();
    private final Map<String, Map<String, Object>> types = new LinkedHashMap<String, Map<String, Object>>();
    /** custom type name or table key -> the DSL name it came from */
    final Map<String, String> dslNames = new LinkedHashMap<String, String>();
    /** domain directory -> table keys, for a layout that groups them */
    final Map<String, List<String>> tablesByDomain = new LinkedHashMap<String, List<String>>();
    private ECoreModelContext ctx;

    private static final class Unit {
        final ECoreModelContext ctx;
        final String domain;
        final ECoreModel model;
        final Entity entity;
        String key;

        Unit(ECoreModelContext ctx, String domain, ECoreModel model, Entity entity) {
            this.ctx = ctx;
            this.domain = domain;
            this.model = model;
            this.entity = entity;
        }
    }

    private DslReader(String database, List<String> warnings) {
        this.database = database == null ? "postgresql" : database;
        this.warnings = warnings;
        // ChartDB draws enum and composite types for PostgreSQL only (database-capabilities.ts).
        this.customTypes = "postgresql".equals(this.database);
    }

    /**
     * @param blueprintRoot the directory that holds one directory per domain, each with an env.yml
     * @param database      the diagram's databaseType, which decides how each DSL type is named
     * @param onlyDomain    the one domain directory to draw, or null for all of them. Every domain is
     *                      loaded either way: a reference into another domain has to resolve
     */
    static DslReader read(File blueprintRoot, String database, List<String> warnings, String onlyDomain) {
        DslReader reader = new DslReader(database, warnings);
        reader.onlyDomain = onlyDomain;
        // timestamp and blob are types the 3.0 rule engine resolves; read as it reads them.
        boolean before = BaseFieldType.ruleEngineTypes();
        BaseFieldType.ruleEngineTypes(true);
        try {
            ConsoleLog.clearKey("DSL_WARN");
            ConsoleLog.clearKey("DSL_ERROR");
            BlueprintLoader loader = BlueprintLoader.load(blueprintRoot.getAbsolutePath());
            if (loader.isEmpty()) {
                throw new IllegalArgumentException("No domain under " + blueprintRoot
                        + ": a domain is a directory with an env.yml");
            }
            reader.collect(loader);
            reader.build();
            for (String key : new String[]{"DSL_ERROR", "DSL_WARN"}) {
                reader.dslMessages.addAll(stored(key));
            }
        } finally {
            BaseFieldType.ruleEngineTypes(before);
        }
        return reader;
    }

    private String onlyDomain;

    /** What the loader said about the DSL while reading it. */
    final List<String> dslMessages = new ArrayList<String>();

    @SuppressWarnings("unchecked")
    private static List<String> stored(String key) {
        try {
            java.lang.reflect.Field f = ConsoleLog.class.getDeclaredField("consoleLogMap");
            f.setAccessible(true);
            Object logs = ((Map<String, Object>) f.get(null)).get(key);
            if (logs instanceof List) {
                List<String> out = new ArrayList<String>();
                for (Object o : (List<Object>) logs) out.add(key + ": " + o);
                return out;
            }
        } catch (Exception ignored) {
            // The count below still says that there were some.
        }
        List<String> out = new ArrayList<String>();
        int n = ConsoleLog.countStoredLogkey(key);
        if (n > 0) out.add(key + ": " + n + " message(s)");
        return out;
    }

    private final Map<String, Object> diagram = new LinkedHashMap<String, Object>();

    Map<String, Object> diagram() {
        return diagram;
    }

    // ---- the model ---------------------------------------------------------------------

    private void collect(BlueprintLoader loader) {
        for (Map.Entry<String, ECoreModelContext> e : loader.contexts().entrySet()) {
            for (ECoreModel model : loader.modelsOf(e.getKey())) {
                EntityModels entities = model.getEntityModels();
                if (entities == null) continue;
                ModelComponentItems<Entity> items = entities.getItems();
                for (int i = 0; i < items.size(); i++) {
                    Entity entity = items.get(i);
                    if (entity == null) continue;
                    Unit u = new Unit(e.getValue(), loader.domainDirNameOf(e.getKey()), model, entity);
                    unitOf.put(entity, u);
                    if (isTable(entity)) {
                        u.key = Names.key(schema(u), table(u));
                        if (onlyDomain == null || onlyDomain.equals(u.domain)) units.add(u);
                    }
                }
                if (onlyDomain == null || onlyDomain.equals(loader.domainDirNameOf(e.getKey()))) {
                    models.add(new Unit(e.getValue(), loader.domainDirNameOf(e.getKey()), model, null));
                }
            }
        }
    }

    /** Every domain drawn, for the types it declares whether or not a table uses them. */
    private final List<Unit> models = new ArrayList<Unit>();

    private void build() {
        List<String> domains = new ArrayList<String>();
        List<Object> tables = new ArrayList<Object>();
        if (customTypes) {
            // Every enumeration and embeddable, in the order the model declares them.
            for (Unit m : models) {
                ctx = m.ctx;
                if (m.model.getEnumModels() != null) {
                    ModelComponentItems<EnumModel> enums = m.model.getEnumModels().getItems();
                    for (int i = 0; i < enums.size(); i++) if (enums.get(i) != null) enumType(enums.get(i));
                }
                if (m.model.getEntityModels() != null) {
                    ModelComponentItems<Entity> items = m.model.getEntityModels().getItems();
                    for (int i = 0; i < items.size(); i++) {
                        Entity e = items.get(i);
                        if (e != null && e.getMetaInfo().hasMetaAnnotation("embeddable")) compositeType(e);
                    }
                }
            }
        }
        for (Unit u : units) {
            ctx = u.ctx;
            if (!domains.contains(u.domain)) domains.add(u.domain);
            tables.add(table(u, tables.size()));
            dslNames.put(u.key, u.entity.getIdentity().getName());
            List<String> keys = tablesByDomain.get(u.domain);
            if (keys == null) tablesByDomain.put(u.domain, keys = new ArrayList<String>());
            keys.add(u.key);
        }
        StringBuilder name = new StringBuilder();
        for (String d : domains) name.append(name.length() > 0 ? ", " : "").append(d);
        diagram.put("name", name.toString());
        diagram.put("databaseType", database);
        diagram.put("tables", tables);
        diagram.put("relationships", new ArrayList<Object>(relationships));
        diagram.put("dependencies", new ArrayList<Object>());
        diagram.put("areas", new ArrayList<Object>());
        diagram.put("customTypes", new ArrayList<Object>(types.values()));
        diagram.put("notes", new ArrayList<Object>());
    }

    /** An entity with a table of its own: not an abstract parent, not a value embedded in another's. */
    private static boolean isTable(Entity entity) {
        return !entity.getMetaInfo().hasMetaAnnotation("abstract")
                && !entity.getMetaInfo().hasMetaAnnotation("embeddable");
    }

    private static String table(Unit u) {
        // The prefix applies only where the DSL named no table - the answer the generated @Table gives.
        String declared = u.entity.getMetaInfo().getMetaAnnotationValue("table");
        if (declared != null) return declared;
        String prefix = u.model.getConfig("table.prefix");
        return (prefix != null && !prefix.isEmpty() ? prefix : "") + u.entity.getTableName();
    }

    private static String schema(Unit u) {
        String declared = u.entity.getMetaInfo().getMetaAnnotationValue("schema");
        if (declared != null && !declared.isEmpty()) return declared;
        String domainWide = u.model.getConfig("tableSchema");
        return domainWide != null && !domainWide.isEmpty() ? domainWide : null;
    }

    // ---- a table -------------------------------------------------------------------------

    private Map<String, Object> table(Unit u, int order) {
        Entity entity = u.entity;
        Map<String, Object> t = new LinkedHashMap<String, Object>();
        t.put("name", table(u));
        String schema = schema(u);
        if (schema != null) t.put("schema", schema);
        t.put("x", 0);
        t.put("y", 0);

        // The fields, keyed by column: a column is in a table once.
        Map<String, Map<String, Object>> fields = new LinkedHashMap<String, Map<String, Object>>();
        List<Field> all = fieldsOf(entity);
        for (Field f : all) {
            if (f.hasAnnotation("transient") || f.hasAnnotation("formula")) continue;
            EnumModel en = enumOf(f);
            Entity target = en == null ? entityOf(f) : null;
            if (f.getTypeInfo().isList()) {
                manyToMany(u, f, all);
            } else if (f.getTypeInfo().isBaseType() || en != null || target == null) {
                put(fields, column(f, columnOf(f), en), f, entity);
                foreignKey(u, f);
            } else if (target.getMetaInfo().hasMetaAnnotation("embeddable")) {
                embedded(fields, f, target, entity);
            } else if (isTable(target)) {
                reference(u, fields, f, target, all);
            }
        }
        t.put("fields", new ArrayList<Object>(fields.values()));
        t.put("indexes", indexes(entity, fields));

        List<Object> checks = values(entity.getMetaInfo().getMetaAnnotation("check"));
        if (!checks.isEmpty()) t.put("checkConstraints", checks);

        boolean view = entity.getMetaInfo().hasMetaAnnotation("immutable");
        t.put("color", view ? VIEW_COLOR : DEFAULT_TABLE_COLOR);
        t.put("isView", view);
        String label = comment(entity.getMetaInfo().getMetaAnnotationValue("label"),
                entity.getMetaInfo().getMetaAnnotationValue("desc"));
        if (label != null) t.put("comments", label);
        t.put("order", order);
        return t;
    }

    @SuppressWarnings("unchecked")
    private static List<Field> fieldsOf(Entity entity) {
        return new ArrayList<Field>((java.util.Collection<Field>) (java.util.Collection<?>) entity.getAllFieldListMap().values());
    }

    private void put(Map<String, Map<String, Object>> fields, Map<String, Object> column, Field f, Entity entity) {
        String name = String.valueOf(column.get("name"));
        if (fields.containsKey(name)) {
            warnings.add(entity.getIdentity().getName() + "." + f.getName() + " maps to column " + name
                    + ", which another field already maps; it is drawn once");
            return;
        }
        fields.put(name, column);
    }

    /** {@code -- label | description}, as the DSL line had it. */
    private static String comment(String label, String desc) {
        if (label == null || label.trim().isEmpty()) return desc == null || desc.trim().isEmpty() ? null : desc.trim();
        return desc == null || desc.trim().isEmpty() ? label.trim() : label.trim() + " | " + desc.trim();
    }

    /** The column a value field is stored in, as the generated entity maps it. */
    private static String columnOf(Field f) {
        String declared = f.getAnnotationValue("column", "col", "db");
        if (declared != null && !declared.isEmpty()) return declared;
        String db = f.getDbColumnName();
        return db != null ? db : Names.snake(f.getName());
    }

    private static boolean isKey(Field f) {
        return f.isPrimaryKey() || f.hasAnnotation("id") || f.hasAnnotation("pk");
    }

    private static boolean required(Field f) {
        return f.hasAnnotation("notnull", "notblank", "required", "notempty");
    }

    private static boolean unique(Field f) {
        return f.hasAnnotation("unique") && !"false".equals(f.getAnnotationValue("unique"));
    }

    // ---- a column ------------------------------------------------------------------------

    /** The DSL type a field was declared with, as one of {@link SqlTypes}' words. */
    private static String dslType(Field f) {
        BaseFieldType base = f.getTypeInfo().getBaseFieldType();
        if (base == null) return SqlTypes.STRING;
        switch (base) {
            case STRING:
            case STRINGARRAY:
                if (f.hasAnnotation("longtext")) return SqlTypes.LONGTEXT;
                if (f.hasAnnotation("text")) return SqlTypes.TEXT;
                if (f.hasAnnotation("lob")) return SqlTypes.CLOB;
                return SqlTypes.STRING;
            case P_INT:
            case INTEGER:
            case INTEGERARRAY:
            case QUANTITY:
                return SqlTypes.INT;
            case P_LONG:
            case LONG:
                return SqlTypes.LONG;
            case BIGINTEGER:
                return SqlTypes.BIGINTEGER;
            case BIGDECIMAL:
            case MONEY:
                return SqlTypes.DECIMAL;
            case P_FLOAT:
            case FLOAT:
                return SqlTypes.FLOAT;
            case P_DOUBLE:
            case DOUBLE:
                return SqlTypes.DOUBLE;
            case P_BOOLEAN:
            case BOOLEAN:
                return SqlTypes.BOOLEAN;
            case DATE:
            case LocalDate:
                return SqlTypes.DATE;
            case TIME:
            case LocalTime:
                return SqlTypes.TIME;
            case DATETIME:
            case LocalDateTime:
                return SqlTypes.DATETIME;
            case INSTANT:
                return SqlTypes.TIMESTAMP;
            case BYTES:
                return SqlTypes.BLOB;
            default:
                return SqlTypes.STRING;
        }
    }

    /** A column type and its size: the one {@code @columnDef} names, or the one the DSL type stands for. */
    private SqlTypes.Column sqlType(Field f) {
        String declared = unquote(f.getAnnotationValue("columndef"));
        if (declared != null && !declared.trim().isEmpty()) return SqlTypes.parse(declared);

        String dsl = dslType(f);
        SqlTypes.Column c = new SqlTypes.Column();
        c.id = SqlTypes.canonical(database, dsl);
        c.name = displayName(c.id);
        BaseFieldType base = f.getTypeInfo().getBaseFieldType();
        c.array = base == BaseFieldType.STRINGARRAY || base == BaseFieldType.INTEGERARRAY;
        if (SqlTypes.STRING.equals(dsl) && f.getLength() > 0) c.length = String.valueOf(f.getLength());
        if (SqlTypes.DECIMAL.equals(dsl) && f.getTypeInfo().getPrecision() > 0) {
            c.precision = f.getTypeInfo().getPrecision();
            c.scale = Math.max(f.getTypeInfo().getScale(), 0);
        }
        return c;
    }

    /** A type id as ChartDB shows it: the ids of two-word types join the words with an underscore. */
    static String displayName(String id) {
        if ("double_precision".equals(id) || id.startsWith("timestamp_with") || id.startsWith("interval_")
                || "long_raw".equals(id)) {
            return id.replace('_', ' ');
        }
        return id;
    }

    private Map<String, Object> column(Field f, String name, EnumModel en) {
        Map<String, Object> c = new LinkedHashMap<String, Object>();
        c.put("name", name);
        SqlTypes.Column sql = null;
        if (en != null && unquote(f.getAnnotationValue("columndef")) == null) {
            if (customTypes) {
                String type = enumType(en);
                c.put("type", type(type, type));
            } else {
                // No enum type to draw in this database: the column holds the stored code.
                sql = new SqlTypes.Column();
                sql.id = SqlTypes.canonical(database, SqlTypes.STRING);
                sql.name = sql.id;
                int longest = 1;
                for (String v : enumValues(en)) longest = Math.max(longest, v.length());
                sql.length = String.valueOf(longest);
                c.put("type", type(sql.id, sql.name));
            }
        } else {
            sql = sqlType(f);
            c.put("type", type(sql.id, sql.name));
        }
        boolean key = isKey(f);
        c.put("primaryKey", key);
        c.put("unique", key || unique(f));
        c.put("nullable", !(key || required(f)));
        if (f.hasAnnotation("sequence")) c.put("increment", true);
        if (sql != null) {
            if (sql.array) c.put("isArray", true);
            if (sql.length != null) c.put("characterMaximumLength", sql.length);
            if (sql.precision != null) {
                c.put("precision", sql.precision);
                if (sql.scale != null) c.put("scale", sql.scale);
            }
        }
        String def = defaultOf(f, en);
        if (def != null) c.put("default", def);
        String label = comment(f.getAnnotationValue("label"), f.getAnnotationValue("desc"));
        if (label != null) c.put("comments", label);
        String check = checkOf(f, name);
        if (check != null) c.put("check", check);
        return c;
    }

    private static Map<String, Object> type(String id, String name) {
        Map<String, Object> t = new LinkedHashMap<String, Object>();
        t.put("id", id);
        t.put("name", name);
        return t;
    }

    /** {@code @default(...)} as the column's DEFAULT clause. */
    private String defaultOf(Field f, EnumModel en) {
        Annotation a = f.getAnnotation("default");
        if (a == null || a.value == null) return null;
        String raw = a.value.trim();
        if (raw.isEmpty()) return null;
        if (en != null) {
            String constant = raw.substring(raw.lastIndexOf('.') + 1);
            String value = enumValue(en, constant);
            return value == null ? null : "'" + value.replace("'", "''") + "'";
        }
        String dsl = dslType(f);
        if ("now".equalsIgnoreCase(raw)) {
            if (SqlTypes.DATE.equals(dsl) || SqlTypes.TIME.equals(dsl) || SqlTypes.DATETIME.equals(dsl)
                    || SqlTypes.TIMESTAMP.equals(dsl)) {
                return "postgresql".equals(database) || "cockroachdb".equals(database) ? "now()" : "CURRENT_TIMESTAMP";
            }
        }
        boolean text = SqlTypes.STRING.equals(dsl) || SqlTypes.TEXT.equals(dsl) || SqlTypes.LONGTEXT.equals(dsl)
                || SqlTypes.CLOB.equals(dsl);
        if (text) return "'" + unquote(raw).replace("'", "''") + "'";
        return unquote(raw);
    }

    /** {@code @check("...")}, or the range {@code @min} and {@code @max} keep. */
    private static String checkOf(Field f, String column) {
        List<Object> declared = values(f.getAnnotation("check"));
        if (!declared.isEmpty()) return String.valueOf(declared.get(0));
        String min = first(f.getAnnotationValue("min"));
        String max = first(f.getAnnotationValue("max"));
        if (min != null && max != null) return column + " >= " + min + " AND " + column + " <= " + max;
        if (min != null) return column + " >= " + min;
        if (max != null) return column + " <= " + max;
        return null;
    }

    /** The number of {@code @min(1, 'message')}. */
    private static String first(String value) {
        if (value == null) return null;
        String v = value.trim();
        int comma = v.indexOf(',');
        if (comma >= 0) v = v.substring(0, comma).trim();
        return v.matches("-?\\d+(\\.\\d+)?") ? v : null;
    }

    /** Every value of an annotation written more than once: {@code @check("a") @check("b")}. */
    private static List<Object> values(Annotation annotation) {
        List<Object> out = new ArrayList<Object>();
        if (annotation == null) return out;
        for (Annotation each : annotation.getSiblings()) {
            String v = unquote(each.value);
            if (v != null && !v.trim().isEmpty()) out.add(v);
        }
        return out;
    }

    static String unquote(String value) {
        if (value == null) return null;
        String v = value.trim();
        if (v.length() >= 2 && (v.charAt(0) == '\'' && v.charAt(v.length() - 1) == '\''
                || v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"')) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    // ---- embedded values ---------------------------------------------------------------

    /** A composite type and one column of it where the database has such types; its columns where it does not. */
    private void embedded(Map<String, Map<String, Object>> fields, Field owner, Entity value, Entity entity) {
        String prefix = owner.getAnnotationValue("embedded.prefix");
        if (prefix == null) {
            String v = owner.getAnnotationValue("embedded");
            if (v != null && !v.isEmpty()) prefix = v;
        }
        if (customTypes) {
            String type = compositeType(value);
            Map<String, Object> c = new LinkedHashMap<String, Object>();
            c.put("name", prefix != null ? prefix : Names.snake(owner.getName()));
            c.put("type", type(type, type));
            c.put("primaryKey", false);
            c.put("unique", false);
            c.put("nullable", !required(owner));
            String label = comment(owner.getAnnotationValue("label"), owner.getAnnotationValue("desc"));
            if (label != null) c.put("comments", label);
            put(fields, c, owner, entity);
            return;
        }
        for (Field f : fieldsOf(value)) {
            if (f.getTypeInfo().isList()) continue;
            EnumModel en = enumOf(f);
            if (!f.getTypeInfo().isBaseType() && en == null) continue;
            String column = prefix != null ? prefix + "_" + Names.snake(f.getName()) : columnOf(f);
            Map<String, Object> c = column(f, column, en);
            if (!required(owner)) c.put("nullable", true);
            put(fields, c, owner, entity);
        }
    }

    private String compositeType(Entity value) {
        String name = Names.snake(value.getIdentity().getName());
        if (types.containsKey(name)) return name;
        Map<String, Object> t = new LinkedHashMap<String, Object>();
        t.put("name", name);
        t.put("kind", "composite");
        List<Object> fields = new ArrayList<Object>();
        for (Field f : fieldsOf(value)) {
            if (f.getTypeInfo().isList()) continue;
            EnumModel en = enumOf(f);
            if (!f.getTypeInfo().isBaseType() && en == null) continue;
            Map<String, Object> each = new LinkedHashMap<String, Object>();
            each.put("field", columnOf(f));
            each.put("type", en != null ? enumType(en) : sqlType(f).sql());
            fields.add(each);
        }
        t.put("fields", fields);
        types.put(name, t);
        dslNames.put(name, value.getIdentity().getName());
        return name;
    }

    // ---- enumerations -----------------------------------------------------------------

    private String enumType(EnumModel en) {
        String name = Names.snake(en.getIdentity().getName());
        if (types.containsKey(name)) return name;
        Map<String, Object> t = new LinkedHashMap<String, Object>();
        t.put("name", name);
        t.put("kind", "enum");
        t.put("values", new ArrayList<Object>(enumValues(en)));
        types.put(name, t);
        dslNames.put(name, en.getIdentity().getName());
        return name;
    }

    /** What the column stores for each constant: the {@code @db} field's value, or the constant's name. */
    private static List<String> enumValues(EnumModel en) {
        List<String> out = new ArrayList<String>();
        int stored = storedIndex(en);
        if (en.getEnumConstantItems() == null) return out;
        for (EnumConstant c : en.getEnumConstantItems().getItemList()) {
            out.add(valueOf(c, stored));
        }
        return out;
    }

    private static String enumValue(EnumModel en, String constant) {
        int stored = storedIndex(en);
        if (en.getEnumConstantItems() == null) return null;
        for (EnumConstant c : en.getEnumConstantItems().getItemList()) {
            if (c.getIdentity().getName().equals(constant)) return valueOf(c, stored);
        }
        return null;
    }

    private static String valueOf(EnumConstant c, int stored) {
        List<EnumConstant.ConstructParam> params = c.getConstructParamList();
        if (stored >= 0 && params != null && stored < params.size() && params.get(stored).getName() != null) {
            return unquote(params.get(stored).getName());
        }
        return c.getIdentity().getName();
    }

    /** The position of the field {@code @db(field)} or {@code @code} names, or -1 where the name is stored. */
    private static int storedIndex(EnumModel en) {
        if (en.getItems() == null) return -1;
        String db = en.getMetaInfo().getMetaAnnotationValue("db");
        int i = 0;
        for (Object o : en.getAllFieldListMap().values()) {
            Field f = (Field) o;
            if (db != null ? f.getName().equals(db) : f.hasAnnotation("code")) return i;
            i++;
        }
        return -1;
    }

    private EnumModel enumOf(Field f) {
        if (f.getTypeInfo().isBaseType() || f.getTypeInfo().isList()) return null;
        return ctx.findModelComponent(f.getTypeInfo().getBaseTypeName(), EnumModel.class);
    }

    private Entity entityOf(Field f) {
        if (f.getTypeInfo().isBaseType()) return null;
        String name = f.getTypeInfo().isList() ? f.getTypeInfo().getTypeParameterName() : f.getTypeInfo().getBaseTypeName();
        return name == null ? null : ctx.findModelComponent(name, Entity.class);
    }

    // ---- relationships -------------------------------------------------------------------

    private static List<Field> keysOf(Entity target) {
        List<Field> keys = new ArrayList<Field>();
        for (Field k : fieldsOf(target)) if (isKey(k) && k.getTypeInfo().isBaseType()) keys.add(k);
        return keys;
    }

    /** A to-one reference: its join column as a field of this table, and the relationship on it. */
    private void reference(Unit u, Map<String, Map<String, Object>> fields, Field f, Entity target, List<Field> all) {
        if (f.hasAnnotation("onetomany") || f.hasAnnotation("manytomany")) return;
        Annotation oneToOne = f.getAnnotation("onetoone");
        if (oneToOne != null && oneToOne.getProperties() != null
                && oneToOne.getProperties().getProperty("mappedBy") != null) return;
        Unit to = unitOf.get(target);
        List<Field> keys = keysOf(target);
        if (keys.isEmpty() || to == null || to.key == null) return;

        String declared = f.getAnnotationValue("column", "col", "db");
        String joinBy = f.getAnnotationValue("joinby", "joinfield");
        Field idField = keys.size() == 1 ? idFieldOf(f, keys.get(0), all) : null;
        boolean shortColumns = "short".equalsIgnoreCase(String.valueOf(u.model.getConfig("referenceColumn")).trim());
        List<String> from = new ArrayList<String>();
        if (declared != null && !declared.isEmpty() && keys.size() == 1) {
            from.add(declared);
        } else if (joinBy != null && !joinBy.isEmpty()) {
            for (Field other : all) if (other.getName().equals(joinBy)) from.add(columnOf(other));
        } else if (idField != null) {
            from.add(columnOf(idField));
        } else if (shortColumns && keys.size() == 1 && heldById(u, f, target)) {
            // config referenceColumn: short - customer_id rather than customer_customer_id
            from.add(Names.snake(idName(f, keys.get(0))));
        } else {
            // JPA's own default: the field's name and the key's column.
            for (Field k : keys) from.add(Names.snake(f.getName()) + "_" + k.getDbColumnName());
        }
        if (from.size() != keys.size()) return;

        boolean one = oneToOne != null;
        for (int i = 0; i < keys.size(); i++) {
            Field key = keys.get(i);
            if (!fields.containsKey(from.get(i))) {
                // The join column: the key's type, never its sequence or its default.
                Map<String, Object> c = new LinkedHashMap<String, Object>();
                c.put("name", from.get(i));
                SqlTypes.Column sql = sqlType(key);
                serialToInteger(sql);
                c.put("type", type(sql.id, sql.name));
                boolean pk = isKey(f);
                c.put("primaryKey", pk);
                c.put("unique", pk || one || unique(f));
                c.put("nullable", !(pk || required(f)));
                if (sql.length != null) c.put("characterMaximumLength", sql.length);
                if (sql.precision != null) {
                    c.put("precision", sql.precision);
                    if (sql.scale != null) c.put("scale", sql.scale);
                }
                String label = comment(f.getAnnotationValue("label"), f.getAnnotationValue("desc"));
                if (label != null) c.put("comments", label);
                fields.put(from.get(i), c);
            }
            relationship(u.key, from.get(i), to.key, key.getDbColumnName(), one ? "one" : "many", "one",
                    schema(u), schema(to));
        }
    }

    /**
     * Whether the rule engine holds this reference by the other entity's id (IdReferences): a
     * reference to another aggregate's root, which is what {@code referenceColumn: short} renames.
     * A member's reference to its own root, or into an aggregate, stays an object with JPA's column.
     */
    private static boolean heldById(Unit u, Field f, Entity target) {
        if (f.hasAnnotation("cascade") || f.hasAnnotation("embedded") || f.hasAnnotation("joinby")
                || f.hasAnnotation("joinfield") || f.hasAnnotation("objectref")) return false;
        String targetName = target.getIdentity().getName();
        if (aggregateOf(targetName, u.model) != null) return false;
        String own = u.entity.getIdentity().getName();
        String ownRoot = aggregateOf(own, u.model);
        return !targetName.equals(ownRoot == null ? own : ownRoot);
    }

    /** The aggregate an entity is a member of, or null for a root - asked of the rule engine, which decides it. */
    private static String aggregateOf(String name, ECoreModel model) {
        try {
            java.lang.reflect.Method m = io.elasticore.base.model.pub.rules.verify.SpecFactory.class
                    .getDeclaredMethod("aggregateOfModel", String.class, ECoreModel.class);
            m.setAccessible(true);
            return (String) m.invoke(null, name, model);
        } catch (Exception e) {
            return null;
        }
    }

    /** A join column on a serial key is the integer the key is, not another sequence. */
    private static void serialToInteger(SqlTypes.Column sql) {
        String id = SqlTypes.normalize(sql.id);
        String plain = "serial".equals(id) ? "int" : "bigserial".equals(id) ? "bigint"
                : "smallserial".equals(id) ? "smallint" : null;
        if (plain != null) {
            sql.id = plain;
            sql.name = plain;
        }
    }

    private static String idName(Field ref, Field targetKey) {
        String key = targetKey.getName();
        return key.startsWith(ref.getName())
                ? key
                : ref.getName() + Character.toUpperCase(key.charAt(0)) + key.substring(1);
    }

    /** The field that holds the reference by its identity - item's itemId - if the entity has one. */
    private static Field idFieldOf(Field ref, Field targetKey, List<Field> all) {
        String idName = idName(ref, targetKey);
        for (Field f : all) {
            if (f.getName().equals(idName) && f.getTypeInfo().isBaseType()) return f;
        }
        return null;
    }

    /** {@code @fk(Target)} or {@code @fk(Target.field)} on a value field: a relationship without a JPA association. */
    private void foreignKey(Unit u, Field f) {
        Annotation fk = f.getAnnotation("fk");
        if (fk == null) fk = f.getAnnotation("foreignkey");
        if (fk == null || fk.value == null) return;
        String spec = fk.value.split(",")[0].trim();
        String targetName = spec.indexOf('.') >= 0 ? spec.substring(0, spec.indexOf('.')) : spec;
        String fieldName = spec.indexOf('.') >= 0 ? spec.substring(spec.indexOf('.') + 1) : null;
        Entity target = ctx.findModelComponent(targetName, Entity.class);
        Unit to = target == null ? null : unitOf.get(target);
        if (to == null || to.key == null) return;
        Field targetField = null;
        for (Field k : fieldsOf(target)) {
            if (fieldName != null ? k.getName().equals(fieldName) : isKey(k)) {
                targetField = k;
                break;
            }
        }
        if (targetField == null || !targetField.getTypeInfo().isBaseType()) return;
        int keys = 0;
        for (Field k : fieldsOf(u.entity)) if (isKey(k)) keys++;
        boolean one = unique(f) || isKey(f) && keys == 1;
        relationship(u.key, columnOf(f), to.key, columnOf(targetField), one ? "one" : "many", "one",
                schema(u), schema(to));
    }

    /** {@code List<Target> @ManyToMany}: a many-to-many line between the two keys. The join table is JPA's. */
    private void manyToMany(Unit u, Field f, List<Field> all) {
        if (!f.hasAnnotation("manytomany")) return;
        Annotation a = f.getAnnotation("manytomany");
        if (a.getProperties() != null && a.getProperties().getProperty("mappedBy") != null) return;
        Entity target = entityOf(f);
        Unit to = target == null ? null : unitOf.get(target);
        if (to == null || to.key == null) return;
        List<Field> own = new ArrayList<Field>();
        for (Field k : all) if (isKey(k) && k.getTypeInfo().isBaseType()) own.add(k);
        List<Field> other = keysOf(target);
        if (own.size() != 1 || other.size() != 1) return;
        relationship(u.key, columnOf(own.get(0)), to.key, columnOf(other.get(0)), "many", "many",
                schema(u), schema(to));
    }

    private void relationship(String source, String sourceField, String target, String targetField,
                              String sourceCardinality, String targetCardinality, String sourceSchema,
                              String targetSchema) {
        Map<String, Object> r = new LinkedHashMap<String, Object>();
        String table = source.substring(source.lastIndexOf('.') + 1);
        r.put("name", "fk_" + table + "_" + sourceField);
        if (sourceSchema != null) r.put("sourceSchema", sourceSchema);
        r.put("sourceTable", source);
        if (targetSchema != null) r.put("targetSchema", targetSchema);
        r.put("targetTable", target);
        r.put("sourceField", sourceField);
        r.put("targetField", targetField);
        r.put("sourceCardinality", sourceCardinality);
        r.put("targetCardinality", targetCardinality);
        relationships.add(r);
    }

    // ---- indexes ---------------------------------------------------------------------------

    /** {@code @Index(name="..", columnList="a,b", unique="true")} on the entity, each as the diagram's index. */
    private List<Object> indexes(Entity entity, Map<String, Map<String, Object>> fields) {
        List<Object> out = new ArrayList<Object>();
        Annotation index = entity.getMetaInfo().getMetaAnnotation("Index", "idx");
        if (index == null) return out;
        for (Annotation each : index.getSiblings()) {
            Properties p = each.getProperties();
            if (p == null) continue;
            String columns = unquote(p.getProperty("columnList"));
            if (columns == null || columns.trim().isEmpty()) continue;
            List<Object> names = new ArrayList<Object>();
            for (String c : columns.split(",")) {
                // A column list may say a direction after the name.
                String col = c.trim().split("\\s+")[0];
                if (col.isEmpty()) continue;
                if (fields.containsKey(col)) {
                    names.add(col);
                } else {
                    warnings.add(entity.getIdentity().getName() + ": @Index " + unquote(p.getProperty("name"))
                            + " names column " + col + ", which the entity does not map");
                }
            }
            Map<String, Object> ix = new LinkedHashMap<String, Object>();
            String name = unquote(p.getProperty("name"));
            ix.put("name", name == null ? "" : name);
            ix.put("unique", "true".equals(unquote(p.getProperty("unique"))));
            ix.put("fields", names);
            out.add(ix);
        }
        return out;
    }
}
