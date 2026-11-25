package io.elasticore.chartdb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The online shop diagram (samples/shop), to a blueprint and back.
 *
 * The diagram uses every construct ChartDB's diagram format has, so this is
 * the test that each has somewhere to go: a line of the DSL, or the sidecar.
 */
class ShopDiagramTest {

    private static File sample() {
        return new File(System.getProperty("chartdb.samples"), "shop/shop.chartdb.json");
    }

    @Test
    void comesBackCharacterForCharacter(@TempDir Path work) throws Exception {
        ChartDbConverter.Result result = ChartDbConverter.verify(sample(), work.toFile());

        assertEquals(new ArrayList<String>(), result.lost, "what the round trip changed");
        assertEquals(new ArrayList<String>(), result.dslMessages, "what the DSL loader said");
        assertEquals(ChartDbConverter.read(sample()),
                ChartDbConverter.read(new File(work.toFile(), "shop.chartdb.json")));
    }

    @Test
    void theModelIsInTheDslAndThePictureInTheSidecar(@TempDir Path work) throws Exception {
        DslWriter.Options options = new DslWriter.Options();
        options.domain = "shop";
        options.basePackage = "com.acme.shop";
        ChartDbConverter.Result result = ChartDbConverter.toYaml(sample(), work.toFile(), options, false);
        assertEquals(new ArrayList<String>(), result.lost);
        assertEquals(28, result.tables);

        String catalog = ChartDbConverter.read(new File(work.toFile(), "shop/model/catalog.yml"));
        // a table and its schema; a column with its size, NOT NULL and UNIQUE; a comment
        assertTrue(catalog.contains("meta: entity @table(products) @schema(catalog)"), catalog);
        assertTrue(catalog.contains("code:         string(40)! @unique -- 상품 코드"), catalog);
        // a foreign key is a reference to the entity; a self reference too
        assertTrue(catalog.contains("category:     Category! -- 분류"), catalog);
        assertTrue(catalog.contains("parent:    Category -- 상위 분류"), catalog);
        // a composite key that is two foreign keys keeps its columns
        assertTrue(catalog.contains("productId: long @id @fk(Product.id) -- 상품"), catalog);
        // an enum column with its default; a column type the DSL lacks; an array
        assertTrue(catalog.contains("status:       ProductStatus! @default(ProductStatus.DRAFT)"), catalog);
        assertTrue(catalog.contains("attributes:   string @columnDef('jsonb')"), catalog);
        assertTrue(catalog.contains("keywords:     string[] @columnDef('varchar(50)[]')"), catalog);
        // an index, a table check, a column check that is also a range
        assertTrue(catalog.contains("@Index(name=\"idx_products_category_status\", columnList=\"category_id,status\")"), catalog);
        assertTrue(catalog.contains("@check(\"sale_price >= 0 AND sale_price <= list_price\")"), catalog);
        assertTrue(catalog.contains("@min(1) @max(5) @check(\"rating BETWEEN 1 AND 5\")"), catalog);
        // a comment with '=' is quoted so that the loader reads it whole
        assertTrue(catalog.contains("-- \"깊이 (1 = 대분류)\""), catalog);

        String sales = ChartDbConverter.read(new File(work.toFile(), "shop/model/sales.yml"));
        assertTrue(sales.contains("order:           Order! @OneToOne -- 주문"), sales);              // one to one
        assertTrue(sales.contains("products:     List<Product> @ManyToMany"), sales);                // many to many
        assertTrue(sales.contains("shippingAddress: PostalAddress! @Embedded -- 배송지"), sales);     // composite type

        String report = ChartDbConverter.read(new File(work.toFile(), "shop/model/report.yml"));
        assertTrue(report.contains("meta: entity @immutable @table(v_product_stock) @schema(report)"), report);

        String enums = ChartDbConverter.read(new File(work.toFile(), "shop/model/enum.yml"));
        assertTrue(enums.contains("RETURN:   \"return\""), enums);       // a value that is no constant name

        // The picture, and what a database knows that the DSL does not say.
        Map<String, Object> side = ChartDbIo.map(Sidecar.read(new File(work.toFile(), Sidecar.FILE_NAME)).get("chartdb"));
        assertEquals(6, ChartDbIo.list(side.get("areas")).size());
        assertEquals(2, ChartDbIo.list(side.get("notes")).size());
        assertEquals(4, ChartDbIo.list(side.get("dependencies")).size());
        Map<String, Object> products = ChartDbIo.map(ChartDbIo.map(side.get("tables")).get("catalog.products"));
        assertNotNull(products.get("x"));
        assertEquals("gin", ChartDbIo.map(ChartDbIo.map(products.get("indexes")).get("idx_products_keywords")).get("type"));
        // What the DSL says is not said twice.
        assertFalse(products.containsKey("name"));
        assertFalse(products.containsKey("comments"));
        assertFalse(ChartDbIo.map(products.get("fields")).containsKey("code"));
        Map<String, Object> view = ChartDbIo.map(ChartDbIo.map(side.get("tables")).get("report.mv_daily_sales"));
        assertEquals(Boolean.TRUE, view.get("isMaterializedView"));
    }

    @Test
    void anEditOfTheDslShowsInTheNextDiagram(@TempDir Path work) throws Exception {
        DslWriter.Options options = new DslWriter.Options();
        options.domain = "shop";
        ChartDbConverter.toYaml(sample(), work.toFile(), options, false);

        // A column added, one renamed table, one new table - in the DSL only.
        File catalog = new File(work.toFile(), "shop/model/catalog.yml");
        String text = ChartDbConverter.read(catalog)
                .replace("      name: string(50)! @unique -- 태그 이름",
                        "      name: string(50)! @unique -- 태그 이름\n      color: string(7) -- 태그 색")
                + "\n  Wishlist:\n    meta: entity @schema(catalog)\n    fields:\n      id: long @id @sequence\n"
                + "      user: User!\n      product: Product!\n";
        ChartDbConverter.write(catalog, text);

        File json = new File(work.toFile(), "edited.json");
        ChartDbConverter.Result result = ChartDbConverter.toJson(work.toFile(), json, null, null);
        assertEquals(new ArrayList<String>(), result.dslMessages);
        assertEquals(29, result.tables);
        assertEquals(36, result.relationships);

        List<String> warnings = new ArrayList<String>();
        Map<String, Object> named = ChartDbIo.named(Json.parseObject(ChartDbConverter.read(json)), warnings);
        assertEquals(new ArrayList<String>(), warnings);
        Map<String, Object> tags = null, wishlist = null, products = null;
        for (Map<String, Object> t : ChartDbIo.maps(named.get("tables"))) {
            if ("tags".equals(t.get("name"))) tags = t;
            if ("wishlist".equals(t.get("name"))) wishlist = t;
            if ("products".equals(t.get("name"))) products = t;
        }
        assertNotNull(tags);
        assertNotNull(wishlist);
        assertEquals(3, ChartDbIo.list(tags.get("fields")).size(), "the new column is drawn");
        // The table the sidecar knows keeps its place; the new one is put under the picture.
        assertEquals(ChartDbIo.map(ChartDbIo.map(ChartDbIo.map(Sidecar.read(new File(work.toFile(), Sidecar.FILE_NAME))
                .get("chartdb")).get("tables")).get("catalog.products")).get("x"), products.get("x"));
        assertTrue(((Number) wishlist.get("y")).doubleValue() > ((Number) products.get("y")).doubleValue());
    }
}
