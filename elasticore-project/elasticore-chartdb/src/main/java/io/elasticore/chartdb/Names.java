package io.elasticore.chartdb;

import io.elasticore.base.util.StringUtils;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Names on both sides: a table and its entity, a column and its field.
 *
 * The DSL derives a column from a field name and a table from an entity name
 * ({@link StringUtils#camelToSnake}). The writer asks here for the DSL name of
 * a database name, then checks whether the DSL would derive the same database
 * name back - where it would not, the database name is written out
 * ({@code @table}, {@code @Column}), so nothing rests on the two rules agreeing.
 */
final class Names {

    private static final Set<String> JAVA_KEYWORDS = new HashSet<String>(Arrays.asList(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
            "volatile", "while", "true", "false", "null", "var", "record", "yield"));

    /** Words the DSL reads as a type, and classes a generated source file already imports. */
    private static final Set<String> RESERVED_TYPES = new HashSet<String>(Arrays.asList(
            "string", "text", "longtext", "clob", "json", "int", "integer", "long", "biginteger", "quantity",
            "decimal", "bigdecimal", "money", "float", "double", "boolean", "date", "time", "datetime",
            "timestamp", "blob", "object", "list", "set", "map", "hashmap", "localdate", "localtime",
            "localdatetime", "instant", "entity", "table", "column", "index", "id", "comment", "page",
            "pageable", "sort", "optional", "class", "enum", "embeddable", "version", "transient",
            "specification", "predicate", "path", "root", "join", "bytes", "uuid", "short", "byte", "char"));

    private Names() {
    }

    /** {@code product_options} or {@code ProductOptions} -> {@code productOptions}. */
    static String camel(String name) {
        String pascal = pascal(name);
        if (pascal.isEmpty()) return pascal;
        // A leading run of capitals is one word: ID -> id, URLPath -> urlPath.
        int run = 0;
        while (run < pascal.length() && Character.isUpperCase(pascal.charAt(run))) run++;
        if (run == pascal.length()) return pascal.toLowerCase();
        if (run > 1) return pascal.substring(0, run - 1).toLowerCase() + pascal.substring(run - 1);
        return Character.toLowerCase(pascal.charAt(0)) + pascal.substring(1);
    }

    /** {@code product_options} -> {@code ProductOptions}; a name already in camel case keeps its capitals. */
    static String pascal(String name) {
        StringBuilder out = new StringBuilder();
        boolean upperNext = true;
        boolean separated = name.indexOf('_') >= 0 || name.indexOf('-') >= 0 || name.indexOf(' ') >= 0
                || name.indexOf('.') >= 0;
        boolean allUpper = name.equals(name.toUpperCase());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isJavaIdentifierPart(c) || c == '_' || c == '$') {
                upperNext = true;
                continue;
            }
            if (upperNext) {
                out.append(Character.toUpperCase(c));
                upperNext = false;
            } else {
                out.append(separated || allUpper ? Character.toLowerCase(c) : c);
            }
        }
        if (out.length() == 0) return "X";
        if (!Character.isJavaIdentifierStart(out.charAt(0))) out.insert(0, 'N');
        return out.toString();
    }

    /** A field name for a column: camel case, and never a Java keyword. */
    static String field(String column) {
        String name = camel(column);
        if (JAVA_KEYWORDS.contains(name)) name = name + "Value";
        return name;
    }

    /** An entity or enumeration name for a table or type: singular, Pascal case, not a word the DSL owns. */
    static String type(String databaseName) {
        String name = pascal(singular(databaseName));
        if (RESERVED_TYPES.contains(name.toLowerCase()) || JAVA_KEYWORDS.contains(name.toLowerCase())) {
            name = name + "Info";
        }
        return name;
    }

    /** A constant name for an enumeration value: {@code in-transit} -> {@code IN_TRANSIT}. */
    static String constant(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 128 && (Character.isLetterOrDigit(c) || c == '_')) {
                if (Character.isUpperCase(c) && i > 0 && Character.isLowerCase(value.charAt(i - 1))) out.append('_');
                out.append(Character.toUpperCase(c));
            } else if (Character.isLetter(c)) {
                out.append(c);
            } else if (out.length() > 0 && out.charAt(out.length() - 1) != '_') {
                out.append('_');
            }
        }
        while (out.length() > 0 && out.charAt(out.length() - 1) == '_') out.setLength(out.length() - 1);
        if (out.length() == 0) return "VALUE";
        if (!Character.isJavaIdentifierStart(out.charAt(0))) out.insert(0, "V_");
        if (JAVA_KEYWORDS.contains(out.toString().toLowerCase()) && out.toString().equals(out.toString().toLowerCase())) {
            out.append('_');
        }
        return out.toString();
    }

    /** Whether an enumeration value can be the constant itself. */
    static boolean isConstant(String value) {
        return value.matches("[A-Z][A-Z0-9_]*");
    }

    static String singular(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith("ies") && name.length() > 4) return name.substring(0, name.length() - 3) + "y";
        if (lower.endsWith("sses") || lower.endsWith("shes") || lower.endsWith("ches") || lower.endsWith("xes")
                || lower.endsWith("uses") && name.length() > 5) {
            return name.substring(0, name.length() - 2);
        }
        if (lower.endsWith("s") && !lower.endsWith("ss") && !lower.endsWith("us") && !lower.endsWith("is")
                && name.length() > 3) {
            return name.substring(0, name.length() - 1);
        }
        return name;
    }

    /** The database name the DSL derives from a DSL name. */
    static String snake(String dslName) {
        return StringUtils.camelToSnake(dslName);
    }

    /** {@code schema.table}, or the table alone where the diagram has no schemas. */
    static String key(Object schema, Object name) {
        String s = schema == null ? "" : String.valueOf(schema).trim();
        return s.isEmpty() ? String.valueOf(name) : s + "." + name;
    }
}
