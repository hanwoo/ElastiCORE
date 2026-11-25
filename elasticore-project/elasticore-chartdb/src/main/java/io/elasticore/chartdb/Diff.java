package io.elasticore.chartdb;

import java.util.List;
import java.util.Map;

/** Where two diagrams differ, one line a difference, by the path to it. */
final class Diff {

    private Diff() {
    }

    static void of(Object before, Object after, String path, List<String> out) {
        if (before instanceof Map && after instanceof Map) {
            Map<?, ?> a = (Map<?, ?>) before, b = (Map<?, ?>) after;
            for (Map.Entry<?, ?> e : a.entrySet()) {
                String p = path + "/" + e.getKey();
                if (!b.containsKey(e.getKey())) {
                    out.add(p + ": gone (was " + brief(e.getValue()) + ")");
                } else {
                    of(e.getValue(), b.get(e.getKey()), p, out);
                }
            }
            for (Map.Entry<?, ?> e : b.entrySet()) {
                if (!a.containsKey(e.getKey())) out.add(path + "/" + e.getKey() + ": new (" + brief(e.getValue()) + ")");
            }
        } else if (before instanceof List && after instanceof List) {
            List<?> a = (List<?>) before, b = (List<?>) after;
            if (a.size() != b.size()) out.add(path + ": " + a.size() + " element(s), now " + b.size());
            for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
                of(a.get(i), b.get(i), path + "[" + label(a.get(i), i) + "]", out);
            }
        } else if (!Sidecar.same(before, after)) {
            out.add(path + ": " + brief(before) + " -> " + brief(after));
        }
    }

    private static String label(Object element, int index) {
        if (element instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) element;
            if (m.get("name") != null && !String.valueOf(m.get("name")).isEmpty()) return String.valueOf(m.get("name"));
        }
        return String.valueOf(index);
    }

    private static String brief(Object value) {
        String s = String.valueOf(value);
        return s.length() > 80 ? s.substring(0, 77) + "..." : s;
    }
}
