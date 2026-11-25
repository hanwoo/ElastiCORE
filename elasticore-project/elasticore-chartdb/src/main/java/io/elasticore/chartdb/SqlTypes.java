package io.elasticore.chartdb;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Column types on both sides.
 *
 * The DSL has seventeen value types; a database has many more, and ChartDB
 * names them per database. Each DSL type has one column type it stands for in
 * a database ({@link #canonical}) - the one the diagram shows when the DSL
 * says nothing else. A column of any other type keeps its type in
 * {@code @columnDef('...')}, which is also what the generated entity declares,
 * so going back gives the same column.
 */
final class SqlTypes {

    /** The DSL's value types (DSL guide, "field types"). */
    static final String STRING = "string", TEXT = "text", LONGTEXT = "longtext", CLOB = "clob", JSON = "json",
            INT = "int", LONG = "long", BIGINTEGER = "biginteger", DECIMAL = "decimal", FLOAT = "float",
            DOUBLE = "double", BOOLEAN = "boolean", DATE = "date", TIME = "time", DATETIME = "datetime",
            TIMESTAMP = "timestamp", BLOB = "blob";

    private static final String[] DSL = {STRING, TEXT, LONGTEXT, CLOB, JSON, INT, LONG, BIGINTEGER, DECIMAL,
            FLOAT, DOUBLE, BOOLEAN, DATE, TIME, DATETIME, TIMESTAMP, BLOB};

    /** database type -> the column type id of each DSL type, in the order of {@link #DSL}. */
    private static final Map<String, String[]> CANONICAL = new HashMap<String, String[]>();

    static {
        String[] postgres = {"varchar", "text", "text", "text", "json", "int", "bigint", "numeric", "numeric",
                "real", "double_precision", "boolean", "date", "time", "timestamp", "timestamptz", "bytea"};
        String[] mysql = {"varchar", "text", "longtext", "longtext", "json", "int", "bigint", "decimal", "decimal",
                "float", "double", "boolean", "date", "time", "datetime", "timestamp", "blob"};
        CANONICAL.put("postgresql", postgres);
        CANONICAL.put("cockroachdb", postgres);
        CANONICAL.put("mysql", mysql);
        CANONICAL.put("mariadb", mysql);
        CANONICAL.put("sql_server", new String[]{"varchar", "text", "text", "text", "json", "int", "bigint",
                "decimal", "decimal", "real", "float", "bit", "date", "time", "datetime2", "datetimeoffset",
                "varbinary"});
        CANONICAL.put("oracle", new String[]{"varchar2", "clob", "clob", "clob", "clob", "number", "number",
                "number", "number", "binary_float", "binary_double", "number", "date", "timestamp", "timestamp",
                "timestamp_with_time_zone", "blob"});
        CANONICAL.put("sqlite", new String[]{"varchar", "text", "text", "text", "json", "integer", "bigint",
                "numeric", "numeric", "real", "double", "boolean", "date", "time", "datetime", "timestamp", "blob"});
        CANONICAL.put("generic", new String[]{"varchar", "text", "text", "text", "json", "int", "bigint",
                "numeric", "decimal", "float", "double", "boolean", "date", "time", "datetime", "timestamp",
                "blob"});
        CANONICAL.put("clickhouse", new String[]{"varchar", "text", "longtext", "longtext", "json", "int",
                "bigint", "int256", "double", "float", "double", "boolean", "date", "time", "datetime",
                "datetime64", "blob"});
    }

    /** Other spellings of a column type, as ChartDB's importers leave them. */
    private static final Map<String, String> ALIASES = new HashMap<String, String>();

    static {
        String[][] pairs = {
                {"integer", "int"}, {"int4", "int"}, {"int8", "bigint"}, {"int2", "smallint"}, {"bool", "boolean"},
                {"character_varying", "varchar"}, {"character", "char"}, {"bpchar", "char"},
                {"float4", "real"}, {"float8", "double_precision"},
                {"timestamp_without_time_zone", "timestamp"}, {"timestamp_with_time_zone", "timestamptz"},
                {"time_without_time_zone", "time"}, {"time_with_time_zone", "timetz"}, {"serial4", "serial"},
                {"serial8", "bigserial"}, {"serial2", "smallserial"}};
        for (String[] p : pairs) ALIASES.put(p[0], p[1]);
    }

    /** column type id -> the DSL type whose values it holds. Unlisted types are text to the application. */
    private static final Map<String, String> FAMILY = new LinkedHashMap<String, String>();

    private static void family(String dsl, String... ids) {
        for (String id : ids) FAMILY.put(id, dsl);
    }

    static {
        family(STRING, "varchar", "char", "nvarchar", "nchar", "varchar2", "nvarchar2", "string", "fixedstring",
                "tinytext", "uuid", "uniqueidentifier", "citext");
        family(TEXT, "text", "mediumtext", "ntext");
        family(LONGTEXT, "longtext");
        family(CLOB, "clob", "nclob");
        family(JSON, "json");
        family(INT, "int", "smallint", "tinyint", "mediumint", "serial", "smallserial", "year", "int16", "int32",
                "int8", "uint8", "uint16");
        family(LONG, "bigint", "bigserial", "int64", "uint32", "uint64");
        family(BIGINTEGER, "int128", "int256", "uint128", "uint256");
        family(DECIMAL, "numeric", "decimal", "number", "money", "smallmoney");
        family(FLOAT, "real", "float", "float32", "binary_float");
        family(DOUBLE, "double_precision", "double", "float64", "binary_double");
        family(BOOLEAN, "boolean", "bit");
        family(DATE, "date", "date32");
        family(TIME, "time", "timetz");
        family(DATETIME, "timestamp", "datetime", "datetime2", "smalldatetime", "datetime64");
        family(TIMESTAMP, "timestamptz", "datetimeoffset", "timestamp_with_time_zone",
                "timestamp_with_local_time_zone");
        family(BLOB, "bytea", "blob", "binary", "varbinary", "tinyblob", "mediumblob", "longblob", "image", "raw",
                "long_raw");
    }

    /** Types whose (n) is a length, not a precision. */
    private static final Set<String> SIZED_BY_LENGTH = new HashSet<String>(Arrays.asList(
            "varchar", "char", "nvarchar", "nchar", "varchar2", "nvarchar2", "binary", "varbinary", "bit",
            "varbit", "fixedstring", "raw", "string"));

    private SqlTypes() {
    }

    static String normalize(String id) {
        String lower = id == null ? "" : id.trim().toLowerCase().replace(' ', '_');
        String alias = ALIASES.get(lower);
        return alias != null ? alias : lower;
    }

    private static String[] table(String database) {
        String[] t = CANONICAL.get(database == null ? "" : database.toLowerCase());
        return t != null ? t : CANONICAL.get("generic");
    }

    /** The column type a DSL type stands for in a database. */
    static String canonical(String database, String dsl) {
        String[] t = table(database);
        for (int i = 0; i < DSL.length; i++) if (DSL[i].equals(dsl)) return t[i];
        return t[0];
    }

    /** The DSL type that holds a column type's values. */
    static String dslOf(String database, String typeId) {
        String id = normalize(typeId);
        String db = database == null ? "" : database.toLowerCase();
        // The same word is a different type in another database.
        if ("timestamp".equals(id) && ("mysql".equals(db) || "mariadb".equals(db) || "sqlite".equals(db)
                || "generic".equals(db))) {
            return TIMESTAMP;
        }
        if ("timestamp".equals(id) && "sql_server".equals(db)) return STRING;      // a row version
        if ("float".equals(id) && "sql_server".equals(db)) return DOUBLE;
        if ("date".equals(id) && "oracle".equals(db)) return DATETIME;
        if ("integer".equals(id)) return INT;
        String dsl = FAMILY.get(id);
        return dsl != null ? dsl : STRING;
    }

    static boolean known(String typeId) {
        String id = normalize(typeId);
        return FAMILY.containsKey(id) || "integer".equals(id);
    }

    static boolean sizedByLength(String typeId) {
        String id = normalize(typeId);
        return SIZED_BY_LENGTH.contains(id) || !FAMILY.containsKey(id);
    }

    /** A column type with its size, as SQL writes it. */
    static final class Column {
        String id;
        String name;
        String length;
        Integer precision;
        Integer scale;
        boolean array;

        /** {@code varchar(50)[]}, {@code numeric(12,2)}, {@code double precision}. */
        String sql() {
            StringBuilder b = new StringBuilder(name != null ? name : id);
            if (length != null && !length.isEmpty()) {
                b.append('(').append(length).append(')');
            } else if (precision != null) {
                b.append('(').append(precision);
                if (scale != null) b.append(',').append(scale);
                b.append(')');
            }
            if (array) b.append("[]");
            return b.toString();
        }
    }

    private static final Pattern SQL = Pattern.compile("^\\s*(.+?)\\s*(?:\\(\\s*([^(),]+?)\\s*(?:,\\s*(\\d+)\\s*)?\\))?\\s*(\\[\\])?\\s*$");

    /** Reads what {@link Column#sql()} writes. */
    static Column parse(String sql) {
        Column c = new Column();
        Matcher m = SQL.matcher(sql == null ? "" : sql);
        if (!m.matches()) {
            c.name = sql;
            c.id = normalizeId(sql);
            return c;
        }
        c.name = m.group(1);
        c.id = normalizeId(c.name);
        c.array = m.group(4) != null;
        String first = m.group(2);
        if (first != null) {
            boolean number = first.matches("\\d+");
            if (m.group(3) != null && number) {
                c.precision = Integer.valueOf(first);
                c.scale = Integer.valueOf(m.group(3));
            } else if (number && !sizedByLength(c.id)) {
                c.precision = Integer.valueOf(first);
            } else {
                c.length = first;
            }
        }
        return c;
    }

    /** The id ChartDB gives a type name: lower case, words joined by an underscore. */
    static String normalizeId(String name) {
        return name == null ? "" : name.trim().toLowerCase().replace(' ', '_');
    }
}
