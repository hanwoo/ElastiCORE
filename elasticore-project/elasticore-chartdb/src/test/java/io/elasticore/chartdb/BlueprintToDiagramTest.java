package io.elasticore.chartdb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Blueprints written by hand - the sample modules' own - as diagrams.
 *
 * They have no sidecar, so everything in the diagram is what the DSL says:
 * the pet store's seven tables under its table prefix, and the three bounded
 * contexts of elasticore-template-ddd as three areas.
 */
class BlueprintToDiagramTest {

    private static Map<String, Object> table(Map<String, Object> named, String name) {
        for (Map<String, Object> t : ChartDbIo.maps(named.get("tables"))) {
            if (name.equals(t.get("name"))) return t;
        }
        return null;
    }

    private static Map<String, Object> field(Map<String, Object> table, String name) {
        for (Map<String, Object> f : ChartDbIo.maps(table.get("fields"))) {
            if (name.equals(f.get("name"))) return f;
        }
        return null;
    }

    private static Map<String, Object> named(File json) throws Exception {
        List<String> warnings = new ArrayList<String>();
        Map<String, Object> named = ChartDbIo.named(Json.parseObject(ChartDbConverter.read(json)), warnings);
        assertEquals(new ArrayList<String>(), warnings, "a reference to nothing in the diagram written");
        return named;
    }

    @Test
    void petStore(@TempDir Path work) throws Exception {
        File blueprint = new File(System.getProperty("chartdb.petstore"));
        File json = new File(work.toFile(), "petstore.json");
        ChartDbConverter.Result result = ChartDbConverter.toJson(blueprint, json, null, null);
        assertEquals(new ArrayList<String>(), result.dslMessages);

        Map<String, Object> named = named(json);
        assertEquals("postgresql", named.get("databaseType"));
        Set<String> tables = new TreeSet<String>();
        for (Map<String, Object> t : ChartDbIo.maps(named.get("tables"))) tables.add(String.valueOf(t.get("name")));
        // env.yml: table.prefix ps_
        assertEquals("[ps_category, ps_country, ps_customer, ps_item, ps_order_line, ps_product, ps_purchase_order]",
                tables.toString());

        Map<String, Object> order = table(named, "ps_purchase_order");
        assertEquals("주문", order.get("comments"));
        // orderId: string(36) @id @genid
        assertEquals("varchar", ChartDbIo.map(field(order, "order_id").get("type")).get("id"));
        assertEquals("36", field(order, "order_id").get("characterMaximumLength"));
        assertEquals(Boolean.TRUE, field(order, "order_id").get("primaryKey"));
        // customer: Customer @ManyToOne, with referenceColumn: short
        assertNotNull(field(order, "customer_id"), String.valueOf(order.get("fields")));
        assertEquals("bigint", ChartDbIo.map(field(order, "customer_id").get("type")).get("id"));
        // status: OrderStatus - an enum type whose values are the stored codes
        assertEquals("order_status", ChartDbIo.map(field(order, "status").get("type")).get("id"));
        // total: money(12.2) @default(0)
        assertEquals("numeric", ChartDbIo.map(field(order, "total").get("type")).get("id"));
        assertEquals(12, field(order, "total").get("precision"));
        assertEquals("0", field(order, "total").get("default"));
        // creditCard: CreditCard! @Embedded - a composite type
        assertEquals("credit_card", ChartDbIo.map(field(order, "credit_card").get("type")).get("id"));
        assertEquals(Boolean.FALSE, field(order, "credit_card").get("nullable"));
        // lines: List<OrderLine> @OneToMany - the other side holds the column
        assertEquals(null, field(order, "lines"));

        // quantity: quantity! @min(1)
        Map<String, Object> line = table(named, "ps_order_line");
        assertEquals("quantity >= 1", field(line, "quantity").get("check"));
        assertEquals(Boolean.FALSE, field(line, "quantity").get("nullable"));

        // @Index(name="uk_ps_customer_login", columnList="login", unique="true")
        Map<String, Object> index = ChartDbIo.maps(table(named, "ps_customer").get("indexes")).get(0);
        assertEquals("uk_ps_customer_login", index.get("name"));
        assertEquals(Boolean.TRUE, index.get("unique"));
        assertEquals("[login]", String.valueOf(index.get("fields")));

        Set<String> relationships = new TreeSet<String>();
        for (Map<String, Object> r : ChartDbIo.maps(named.get("relationships"))) {
            relationships.add(r.get("sourceTable") + "." + r.get("sourceField") + ">" + r.get("targetTable") + "."
                    + r.get("targetField") + " " + r.get("sourceCardinality") + ":" + r.get("targetCardinality"));
        }
        assertEquals("[ps_item.product_id>ps_product.product_id many:one, "
                + "ps_order_line.item_id>ps_item.item_id many:one, "
                + "ps_order_line.order_order_id>ps_purchase_order.order_id many:one, "
                + "ps_product.category_id>ps_category.category_id many:one, "
                + "ps_purchase_order.customer_id>ps_customer.customer_id many:one]", relationships.toString());

        Set<String> types = new HashSet<String>();
        for (Map<String, Object> t : ChartDbIo.maps(named.get("customTypes"))) {
            types.add(t.get("name") + ":" + t.get("kind"));
            if ("order_status".equals(t.get("name"))) assertEquals("[PLC, SHP, CAN]", String.valueOf(t.get("values")));
        }
        assertTrue(types.contains("address:composite") && types.contains("credit_card:composite")
                && types.contains("user_role:enum"), types.toString());
    }

    @Test
    void aDatabaseWithoutCustomTypesGetsTheColumns(@TempDir Path work) throws Exception {
        File json = new File(work.toFile(), "petstore-mysql.json");
        ChartDbConverter.toJson(new File(System.getProperty("chartdb.petstore")), json, "mysql", "Pet Store");
        Map<String, Object> named = named(json);
        assertEquals("Pet Store", named.get("name"));
        assertEquals(0, ChartDbIo.list(named.get("customTypes")).size());

        Map<String, Object> order = table(named, "ps_purchase_order");
        // the embedded address as its columns, the enum as the code it stores, money as decimal
        assertNotNull(field(order, "zipcode"));
        assertEquals("varchar", ChartDbIo.map(field(order, "status").get("type")).get("id"));
        assertEquals("3", field(order, "status").get("characterMaximumLength"));
        assertEquals("decimal", ChartDbIo.map(field(order, "total").get("type")).get("id"));
        assertEquals("date", ChartDbIo.map(field(order, "order_date").get("type")).get("id"));
    }

    @Test
    void boundedContextsAreAreas(@TempDir Path work) throws Exception {
        File blueprint = new File(System.getProperty("chartdb.ddd"));
        File json = new File(work.toFile(), "ddd.json");
        ChartDbConverter.Result result = ChartDbConverter.toJson(blueprint, json, null, null);
        assertEquals(new ArrayList<String>(), result.dslMessages);

        Map<String, Object> named = named(json);
        List<String> areas = new ArrayList<String>();
        for (Map<String, Object> a : ChartDbIo.maps(named.get("areas"))) areas.add(String.valueOf(a.get("name")));
        assertEquals("[billing, catalog, shop]", areas.toString());
        for (Map<String, Object> t : ChartDbIo.maps(named.get("tables"))) {
            assertNotNull(t.get("parentArea"), t.get("name") + " is in no area");
        }
        assertTrue(result.tables >= 10, result.tables + " tables");
    }

    /** A diagram written from a blueprint is a diagram like any other: it converts, and comes back. */
    @Test
    void whatWasWrittenConvertsBack(@TempDir Path work) throws Exception {
        for (String sample : new String[]{"chartdb.petstore", "chartdb.ddd"}) {
            File json = new File(work.toFile(), sample + ".json");
            ChartDbConverter.toJson(new File(System.getProperty(sample)), json, null, null);
            ChartDbConverter.Result result = ChartDbConverter.verify(json, new File(work.toFile(), sample));
            assertEquals(new ArrayList<String>(), result.lost, sample);
            assertEquals(new ArrayList<String>(), result.dslMessages, sample);
        }
    }

    /**
     * Any diagrams there are: -Dchartdb.extra=dir. Each must reach a blueprint the loader accepts
     * and come back as it was. Run over ChartDB's own templates while this module was written.
     */
    @Test
    void everyDiagramOfACorpus(@TempDir Path work) throws Exception {
        String dir = System.getProperty("chartdb.extra", "");
        assumeTrue(!dir.isEmpty() && new File(dir).isDirectory(), "no -Dchartdb.extra");
        List<String> failures = new ArrayList<String>();
        // What went to the sidecar, by kind: a kind that is there often is a line the DSL could have.
        java.util.Map<String, int[]> kept = new java.util.TreeMap<String, int[]>();
        java.util.Map<String, String> example = new java.util.HashMap<String, String>();
        int n = 0;
        File[] files = new File(dir).listFiles();
        java.util.Arrays.sort(files);
        for (File json : files) {
            if (!json.getName().endsWith(".json")) continue;
            n++;
            try {
                ChartDbConverter.Result result = ChartDbConverter.verify(json, new File(work.toFile(), json.getName()));
                for (String lost : result.lost) failures.add(json.getName() + " LOST " + lost);
                for (String m : result.dslMessages) failures.add(json.getName() + " DSL " + m);
                for (Map.Entry<String, List<String>> k : result.kept.entrySet()) {
                    int[] count = kept.get(k.getKey());
                    if (count == null) kept.put(k.getKey(), count = new int[2]);
                    count[0] += k.getValue().size();
                    count[1]++;
                    example.put(k.getKey(), json.getName() + ": " + k.getValue().get(0));
                }
            } catch (Exception e) {
                failures.add(json.getName() + " " + e);
            }
        }
        System.out.println("[chartdb] corpus: " + n + " diagram(s), " + failures.size() + " problem(s)");
        for (String f : failures) System.out.println("[chartdb]   " + f);
        for (Map.Entry<String, int[]> k : kept.entrySet()) {
            System.out.println("[chartdb] kept " + k.getKey() + " x" + k.getValue()[0] + " in " + k.getValue()[1]
                    + " diagram(s), e.g. " + example.get(k.getKey()));
        }
        assertEquals(new ArrayList<String>(), failures);
    }
}
