package io.elasticore.chartdb;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON as ChartDB writes it.
 *
 * A diagram is read into plain maps and lists, in the order of the file, and
 * written the way {@code JSON.stringify(diagram, null, 2)} writes it - which
 * is what ChartDB's "Export &gt; JSON" does. Writing it the same way is what
 * lets a converted file be compared with an exported one line by line.
 */
final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parseObject(String text) {
        try {
            return MAPPER.readValue(text, LinkedHashMap.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("Not a JSON object: " + e.getMessage(), e);
        }
    }

    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value, 0);
        return out.toString();
    }

    private static void write(StringBuilder out, Object value, int depth) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String) {
            string(out, (String) value);
        } else if (value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Number) {
            out.append(number((Number) value));
        } else if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            if (map.isEmpty()) {
                out.append("{}");
                return;
            }
            out.append("{\n");
            int i = 0;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                indent(out, depth + 1);
                string(out, String.valueOf(e.getKey()));
                out.append(": ");
                write(out, e.getValue(), depth + 1);
                if (++i < map.size()) out.append(',');
                out.append('\n');
            }
            indent(out, depth);
            out.append('}');
        } else if (value instanceof List) {
            List<?> list = (List<?>) value;
            if (list.isEmpty()) {
                out.append("[]");
                return;
            }
            out.append("[\n");
            for (int i = 0; i < list.size(); i++) {
                indent(out, depth + 1);
                write(out, list.get(i), depth + 1);
                if (i + 1 < list.size()) out.append(',');
                out.append('\n');
            }
            indent(out, depth);
            out.append(']');
        } else {
            string(out, String.valueOf(value));
        }
    }

    private static void indent(StringBuilder out, int depth) {
        for (int i = 0; i < depth; i++) out.append("  ");
    }

    /** A number as JavaScript prints it: 100 for 100.0, no exponent for the sizes a diagram holds. */
    static String number(Number n) {
        if (n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte) {
            return String.valueOf(n.longValue());
        }
        double d = n.doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d)) return "null";
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
        String plain = BigDecimal.valueOf(d).toPlainString();
        if (plain.indexOf('.') >= 0) {
            plain = plain.replaceAll("0+$", "");
            if (plain.endsWith(".")) plain = plain.substring(0, plain.length() - 1);
        }
        return plain;
    }

    private static void string(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                case '\b':
                    out.append("\\b");
                    break;
                case '\f':
                    out.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }
}
