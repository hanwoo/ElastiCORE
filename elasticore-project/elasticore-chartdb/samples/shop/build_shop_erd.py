#!/usr/bin/env python3
#!/usr/bin/env python3
"""Builds samples/shop/shop_chartdb.json - an online shop ERD for ChartDB.

The diagram is written to exercise every construct ChartDB's diagram format
has (src/lib/domain/*.ts of chartdb/chartdb): schemas, tables, views and a
materialized view, fields with every attribute, indexes of each kind, check
constraints, relationships of all four cardinalities, view dependencies,
areas, notes, and enum and composite custom types.

    python build_shop_erd.py            # writes shop_chartdb.json next to it

Ids are running numbers and every createdAt is one fixed instant, so the file
is the same on every run and a converter's round trip can be compared byte
for byte. ChartDB assigns its own ids on import.
"""
import io
import json
import os

STAMP = 1793836800000  # 2026-11-05T00:00:00Z
_ids = iter(range(1, 100000))


def nid():
    return str(next(_ids))


# ChartDB's palette (src/lib/colors.ts)
RED, PINK, PURPLE, LILAC, INDIGO, BLUE2, SKY, AQUA, MINT, LIME, YELLOW, ORANGE = (
    '#ff6363', '#ff6b8a', '#c05dcf', '#b067e9', '#8a61f5', '#7175fa',
    '#8eb7ff', '#42e0c0', '#4dee8a', '#9ef07a', '#ffe374', '#ff9f74')
VIEW_COLOR, MVIEW_COLOR = '#b0b0b0', '#7d7d7d'

TYPE_NAMES = {'double_precision': 'double precision'}


def f(name, type_, flags='', **kw):
    """One field. flags: P primary key, U unique, N nullable, I increment, A array."""
    field = {
        'id': nid(),
        'name': name,
        'type': {'id': type_, 'name': TYPE_NAMES.get(type_, type_)},
        'primaryKey': 'P' in flags,
        'unique': 'U' in flags or 'P' in flags,
        'nullable': 'N' in flags,
        'createdAt': STAMP,
    }
    if 'I' in flags:
        field['increment'] = True
    if 'A' in flags:
        field['isArray'] = True
    if 'len' in kw:
        field['characterMaximumLength'] = str(kw['len'])
    if 'p' in kw:
        field['precision'] = kw['p']
        field['scale'] = kw.get('s', 0)
    for key in ('default', 'collation', 'comments', 'check'):
        if key in kw:
            field[key] = kw[key]
    return field


TABLES = []
BY_NAME = {}


def table(schema, name, color, comments, fields, indexes=(), checks=(), view=None):
    t = {
        'id': nid(),
        'name': name,
        'schema': schema,
        'x': 0,
        'y': 0,
        'fields': fields,
        'indexes': [],
        'color': color,
        'isView': view is not None,
        'isMaterializedView': view == 'materialized',
        'createdAt': STAMP,
        'width': 337,
        'comments': comments,
        'order': len(TABLES),
        'expanded': False,
    }
    by_field = dict((x['name'], x['id']) for x in fields)
    for ix in indexes:
        entry = {
            'id': nid(),
            'name': ix[0],
            'unique': 'unique' in ix[2:],
            'fieldIds': [by_field[c] for c in ix[1]],
            'createdAt': STAMP,
        }
        for word in ix[2:]:
            if word in ('btree', 'hash', 'gin'):
                entry['type'] = word
            elif word.startswith('--'):
                entry['comments'] = word[2:].strip()
        t['indexes'].append(entry)
    pk = [x['id'] for x in fields if x['primaryKey']]
    if len(pk) > 1:
        # A composite key carries its constraint name in an index entry.
        t['indexes'].append({'id': nid(), 'name': 'pk_' + name, 'unique': True, 'fieldIds': pk,
                             'createdAt': STAMP, 'isPrimaryKey': True})
    if checks:
        t['checkConstraints'] = [{'id': nid(), 'expression': c, 'createdAt': STAMP} for c in checks]
    TABLES.append(t)
    BY_NAME[name] = t
    return t


# ---- member: user management ------------------------------------------------

table('member', 'member_grades', LILAC, '회원 등급', [
    f('id', 'bigint', 'PI', comments='등급 ID'),
    f('code', 'varchar', 'U', len=20, comments='등급 코드 (BRONZE, SILVER ...)'),
    f('name', 'varchar', len=50, comments='등급 이름'),
    f('discount_rate', 'numeric', p=5, s=2, default='0', check='discount_rate >= 0 AND discount_rate <= 100',
      comments='할인율 (%)'),
    f('min_purchase_amount', 'numeric', p=12, s=2, default='0', comments='등급 기준 누적 구매액'),
])

table('member', 'users', LILAC, '사용자 (고객과 관리자)', [
    f('id', 'bigint', 'PI', comments='사용자 ID'),
    f('login_id', 'varchar', 'U', len=30, comments='로그인 아이디'),
    f('password_hash', 'varchar', len=255, comments='비밀번호 해시'),
    f('name', 'varchar', len=50, collation='ko-KR-x-icu', comments='이름'),
    f('email', 'varchar', len=100, check="email LIKE '%@%'", comments='이메일'),
    f('phone', 'varchar', 'N', len=20, comments='휴대전화'),
    f('birth_date', 'date', 'N', comments='생년월일'),
    f('role', 'user_role', default="'CUSTOMER'", comments='권한'),
    f('status', 'user_status', default="'ACTIVE'", comments='계정 상태'),
    f('grade_id', 'bigint', 'N', comments='회원 등급'),
    f('marketing_agreed', 'boolean', default='false', comments='마케팅 수신 동의'),
    f('last_login_at', 'timestamptz', 'N', comments='마지막 로그인 시각'),
    f('last_login_ip', 'inet', 'N', comments='마지막 로그인 IP'),
    f('created_at', 'timestamp', default='now()', comments='가입 일시'),
    f('updated_at', 'timestamp', 'N', comments='수정 일시'),
], indexes=[
    ('uk_users_email', ['email'], 'unique', 'btree', '-- 이메일은 한 사람에 하나'),
    ('idx_users_status_role', ['status', 'role'], 'btree'),
    ('idx_users_grade', ['grade_id'], 'hash'),
])

table('member', 'user_profiles', LILAC, '사용자 프로필 (사용자와 1:1)', [
    f('user_id', 'bigint', 'P', comments='사용자 ID'),
    f('nickname', 'varchar', 'NU', len=30, comments='별명'),
    f('profile_image_url', 'varchar', 'N', len=500, comments='프로필 이미지'),
    f('bio', 'text', 'N', comments='자기소개'),
    f('interests', 'text', 'NA', comments='관심 분야'),
    f('preferences', 'jsonb', 'N', default="'{}'", comments='화면 설정'),
])

table('member', 'user_addresses', LILAC, '배송지 주소록', [
    f('id', 'bigint', 'PI', comments='주소 ID'),
    f('user_id', 'bigint', comments='사용자'),
    f('label', 'varchar', len=30, default="'집'", comments='주소 이름 (집, 회사)'),
    f('recipient', 'varchar', len=50, comments='받는 사람'),
    f('phone', 'varchar', len=20, comments='연락처'),
    f('address', 'postal_address', comments='주소'),
    f('is_default', 'boolean', default='false', comments='기본 배송지'),
    f('delivery_memo', 'varchar', 'N', len=200, comments='배송 요청 사항'),
], indexes=[
    ('idx_user_addresses_user', ['user_id', 'is_default'], 'btree'),
])

# ---- catalog: product management --------------------------------------------

table('catalog', 'categories', SKY, '상품 분류 (계층)', [
    f('id', 'bigint', 'PI', comments='분류 ID'),
    f('parent_id', 'bigint', 'N', comments='상위 분류'),
    f('name', 'varchar', len=100, comments='분류 이름'),
    f('depth', 'smallint', default='1', check='depth BETWEEN 1 AND 4', comments='깊이 (1 = 대분류)'),
    f('sort_order', 'int', default='0', comments='정렬 순서'),
    f('is_visible', 'boolean', default='true', comments='노출 여부'),
], indexes=[
    ('idx_categories_parent', ['parent_id', 'sort_order'], 'btree'),
])

table('catalog', 'brands', SKY, '브랜드', [
    f('id', 'serial', 'PI', comments='브랜드 ID'),
    f('name', 'varchar', 'U', len=100, comments='브랜드 이름'),
    f('logo_url', 'varchar', 'N', len=500, comments='로고 이미지'),
    f('country_code', 'char', 'N', len=2, comments='국가 코드 (ISO 3166-1)'),
])

table('catalog', 'products', SKY, '상품', [
    f('id', 'bigint', 'PI', comments='상품 ID'),
    f('category_id', 'bigint', comments='분류'),
    f('brand_id', 'int', 'N', comments='브랜드'),
    f('code', 'varchar', 'U', len=40, comments='상품 코드'),
    f('name', 'varchar', len=200, comments='상품 이름'),
    f('description', 'text', 'N', comments='상세 설명'),
    f('status', 'product_status', default="'DRAFT'", comments='판매 상태'),
    f('list_price', 'numeric', p=12, s=2, comments='정가'),
    f('sale_price', 'numeric', p=12, s=2, comments='판매가'),
    f('tax_rate', 'numeric', p=5, s=2, default='10.00', comments='세율 (%)'),
    f('weight_kg', 'real', 'N', comments='무게 (kg)'),
    f('keywords', 'varchar', 'NA', len=50, comments='검색 키워드'),
    f('attributes', 'jsonb', 'N', comments='상품 속성 (색상, 소재 ...)'),
    f('search_vector', 'tsvector', 'N', comments='전문 검색 벡터'),
    f('sale_start_at', 'timestamp', 'N', comments='판매 시작'),
    f('sale_end_at', 'timestamp', 'N', comments='판매 종료'),
    f('created_at', 'timestamp', default='now()', comments='등록 일시'),
    f('updated_at', 'timestamp', 'N', comments='수정 일시'),
], indexes=[
    ('idx_products_category_status', ['category_id', 'status'], 'btree'),
    ('idx_products_brand', ['brand_id'], 'btree'),
    ('idx_products_keywords', ['keywords'], 'gin', '-- 키워드 배열 검색'),
    ('idx_products_attributes', ['attributes'], 'gin'),
], checks=[
    'list_price >= 0',
    'sale_price >= 0 AND sale_price <= list_price',
    'sale_end_at IS NULL OR sale_end_at > sale_start_at',
])

table('catalog', 'product_options', SKY, '상품 옵션 (재고 단위, SKU)', [
    f('id', 'bigint', 'PI', comments='옵션 ID'),
    f('product_id', 'bigint', comments='상품'),
    f('sku', 'varchar', 'U', len=50, comments='재고 관리 코드'),
    f('option_name', 'varchar', len=100, comments='옵션 이름 (색상/사이즈)'),
    f('option_value', 'varchar', len=100, comments='옵션 값 (블랙/270)'),
    f('additional_price', 'numeric', p=12, s=2, default='0', comments='추가 금액'),
    f('barcode', 'varchar', 'N', len=30, comments='바코드'),
    f('is_active', 'boolean', default='true', comments='판매 여부'),
], indexes=[
    ('uk_product_options_value', ['product_id', 'option_name', 'option_value'], 'unique', 'btree'),
])

table('catalog', 'product_images', SKY, '상품 이미지', [
    f('id', 'bigint', 'PI', comments='이미지 ID'),
    f('product_id', 'bigint', comments='상품'),
    f('url', 'varchar', len=500, comments='이미지 주소'),
    f('alt_text', 'varchar', 'N', len=200, comments='대체 텍스트'),
    f('sort_order', 'int', default='0', comments='정렬 순서'),
    f('is_main', 'boolean', default='false', comments='대표 이미지'),
    f('thumbnail', 'bytea', 'N', comments='썸네일 원본'),
])

table('catalog', 'tags', SKY, '태그', [
    f('id', 'bigint', 'PI', comments='태그 ID'),
    f('name', 'varchar', 'U', len=50, comments='태그 이름'),
])

table('catalog', 'product_tags', SKY, '상품-태그 연결 (복합 키)', [
    f('product_id', 'bigint', 'P', comments='상품'),
    f('tag_id', 'bigint', 'P', comments='태그'),
    f('tagged_at', 'timestamp', default='now()', comments='연결 일시'),
])

table('catalog', 'product_reviews', SKY, '상품 후기', [
    f('id', 'bigint', 'PI', comments='후기 ID'),
    f('product_id', 'bigint', comments='상품'),
    f('user_id', 'bigint', comments='작성자'),
    f('rating', 'smallint', check='rating BETWEEN 1 AND 5', comments='별점 (1-5)'),
    f('title', 'varchar', 'N', len=200, comments='제목'),
    f('content', 'text', comments='내용'),
    f('is_hidden', 'boolean', default='false', comments='숨김 여부'),
    f('created_at', 'timestamp', default='now()', comments='작성 일시'),
], indexes=[
    ('uk_product_reviews_user', ['product_id', 'user_id'], 'unique', 'btree', '-- 상품마다 한 사람이 한 번'),
])

# ---- inventory: stock management -------------------------------------------

table('inventory', 'warehouses', MINT, '창고', [
    f('id', 'bigint', 'PI', comments='창고 ID'),
    f('code', 'varchar', 'U', len=20, comments='창고 코드'),
    f('name', 'varchar', len=100, comments='창고 이름'),
    f('address', 'postal_address', 'N', comments='창고 주소'),
    f('manager_id', 'bigint', 'N', comments='담당자'),
    f('opens_at', 'time', 'N', comments='운영 시작 시각'),
    f('closes_at', 'time', 'N', comments='운영 종료 시각'),
    f('is_active', 'boolean', default='true', comments='운영 여부'),
])

table('inventory', 'suppliers', MINT, '공급사', [
    f('id', 'bigint', 'PI', comments='공급사 ID'),
    f('name', 'varchar', len=100, comments='공급사 이름'),
    f('business_no', 'varchar', 'U', len=12, comments='사업자 등록 번호'),
    f('contact_name', 'varchar', 'N', len=50, comments='담당자'),
    f('contact_phone', 'varchar', 'N', len=20, comments='담당자 연락처'),
    f('lead_time_days', 'int', default='3', comments='납품 소요 일수'),
])

table('inventory', 'stocks', MINT, '재고 (창고 x 옵션)', [
    f('id', 'bigint', 'PI', comments='재고 ID'),
    f('warehouse_id', 'bigint', comments='창고'),
    f('product_option_id', 'bigint', comments='상품 옵션'),
    f('quantity', 'int', default='0', check='quantity >= 0', comments='보유 수량'),
    f('reserved_quantity', 'int', default='0', comments='주문으로 잡힌 수량'),
    f('safety_stock', 'int', default='0', comments='안전 재고'),
    f('location', 'varchar', 'N', len=30, comments='보관 위치 (A-01-03)'),
    f('version', 'bigint', default='0', comments='낙관적 잠금'),
    f('updated_at', 'timestamp', default='now()', comments='수정 일시'),
], indexes=[
    ('uk_stocks_warehouse_option', ['warehouse_id', 'product_option_id'], 'unique', 'btree'),
], checks=[
    'reserved_quantity >= 0 AND reserved_quantity <= quantity',
])

table('inventory', 'stock_movements', MINT, '재고 이동 이력', [
    f('id', 'uuid', 'P', default='gen_random_uuid()', comments='이동 ID'),
    f('stock_id', 'bigint', comments='재고'),
    f('movement_type', 'stock_movement_type', comments='이동 종류'),
    f('quantity', 'int', check='quantity <> 0', comments='변화량 (입고 +, 출고 -)'),
    f('reason', 'varchar', 'N', len=200, comments='사유'),
    f('order_id', 'bigint', 'N', comments='관련 주문'),
    f('created_by', 'bigint', 'N', comments='처리자'),
    f('created_at', 'timestamptz', default='now()', comments='발생 시각'),
], indexes=[
    ('idx_stock_movements_stock', ['stock_id', 'created_at'], 'btree'),
])

table('inventory', 'inbound_orders', MINT, '입고 예정 (발주)', [
    f('id', 'bigserial', 'PI', comments='발주 ID'),
    f('supplier_id', 'bigint', comments='공급사'),
    f('warehouse_id', 'bigint', comments='입고 창고'),
    f('product_option_id', 'bigint', comments='상품 옵션'),
    f('quantity', 'int', check='quantity > 0', comments='발주 수량'),
    f('unit_cost', 'numeric', p=12, s=2, comments='매입 단가'),
    f('status', 'inbound_status', default="'REQUESTED'", comments='진행 상태'),
    f('expected_date', 'date', 'N', comments='입고 예정일'),
    f('received_at', 'timestamp', 'N', comments='입고 일시'),
])

# ---- sales: orders -----------------------------------------------------------

table('sales', 'orders', YELLOW, '주문', [
    f('id', 'bigint', 'PI', comments='주문 ID'),
    f('order_no', 'varchar', 'U', len=30, comments='주문 번호'),
    f('user_id', 'bigint', comments='주문자'),
    f('status', 'order_status', default="'PENDING'", comments='주문 상태'),
    f('total_amount', 'numeric', p=14, s=2, default='0', comments='상품 금액 합계'),
    f('discount_amount', 'numeric', p=14, s=2, default='0', comments='할인 금액'),
    f('shipping_fee', 'numeric', p=10, s=2, default='0', comments='배송비'),
    f('payment_amount', 'numeric', p=14, s=2, default='0', comments='결제 금액'),
    f('shipping_address', 'postal_address', comments='배송지'),
    f('recipient', 'varchar', len=50, comments='받는 사람'),
    f('recipient_phone', 'varchar', len=20, comments='받는 사람 연락처'),
    f('memo', 'varchar', 'N', len=500, comments='주문 메모'),
    f('ordered_at', 'timestamp', default='now()', comments='주문 일시'),
    f('cancelled_at', 'timestamp', 'N', comments='취소 일시'),
], indexes=[
    ('idx_orders_user_ordered', ['user_id', 'ordered_at'], 'btree'),
    ('idx_orders_status', ['status'], 'btree'),
], checks=[
    'payment_amount = total_amount - discount_amount + shipping_fee',
])

table('sales', 'order_items', YELLOW, '주문 상품', [
    f('id', 'bigint', 'PI', comments='주문 상품 ID'),
    f('order_id', 'bigint', comments='주문'),
    f('product_option_id', 'bigint', comments='상품 옵션'),
    f('product_name', 'varchar', len=200, comments='주문 당시 상품 이름'),
    f('unit_price', 'numeric', p=12, s=2, comments='주문 당시 단가'),
    f('quantity', 'int', check='quantity > 0', comments='수량'),
    f('subtotal', 'numeric', p=14, s=2, comments='소계'),
], indexes=[
    ('idx_order_items_order', ['order_id'], 'btree'),
])

table('sales', 'payments', YELLOW, '결제 (주문과 1:1)', [
    f('id', 'bigint', 'PI', comments='결제 ID'),
    f('order_id', 'bigint', 'U', comments='주문'),
    f('method', 'payment_method', comments='결제 수단'),
    f('amount', 'numeric', p=14, s=2, comments='결제 금액'),
    f('status', 'payment_status', default="'READY'", comments='결제 상태'),
    f('pg_transaction_id', 'varchar', 'NU', len=100, comments='PG 거래 번호'),
    f('paid_at', 'timestamptz', 'N', comments='결제 승인 시각'),
    f('raw_response', 'json', 'N', comments='PG 응답 원문'),
])

table('sales', 'promotions', YELLOW, '프로모션 (상품과 N:M)', [
    f('id', 'bigint', 'PI', comments='프로모션 ID'),
    f('name', 'varchar', len=100, comments='프로모션 이름'),
    f('discount_rate', 'numeric', p=5, s=2, comments='할인율 (%)'),
    f('max_discount', 'double_precision', 'N', comments='최대 할인 금액 (근사값)'),
    f('starts_on', 'date', comments='시작일'),
    f('ends_on', 'date', comments='종료일'),
], checks=[
    'ends_on >= starts_on',
])

# ---- shipping: delivery management -----------------------------------------

table('shipping', 'carriers', ORANGE, '택배사', [
    f('id', 'bigint', 'PI', comments='택배사 ID'),
    f('code', 'varchar', 'U', len=20, comments='택배사 코드'),
    f('name', 'varchar', len=50, comments='택배사 이름'),
    f('tracking_url_template', 'varchar', 'N', len=300, comments='배송 조회 주소 ({trackingNo} 치환)'),
    f('is_active', 'boolean', default='true', comments='사용 여부'),
])

table('shipping', 'shipments', ORANGE, '배송', [
    f('id', 'bigint', 'PI', comments='배송 ID'),
    f('order_id', 'bigint', comments='주문'),
    f('carrier_id', 'bigint', 'N', comments='택배사'),
    f('warehouse_id', 'bigint', comments='출고 창고'),
    f('tracking_no', 'varchar', 'N', len=40, comments='운송장 번호'),
    f('status', 'shipment_status', default="'READY'", comments='배송 상태'),
    f('address', 'postal_address', comments='배송지'),
    f('recipient', 'varchar', len=50, comments='받는 사람'),
    f('recipient_phone', 'varchar', len=20, comments='받는 사람 연락처'),
    f('shipped_at', 'timestamp', 'N', comments='출고 일시'),
    f('delivered_at', 'timestamp', 'N', comments='배송 완료 일시'),
], indexes=[
    ('uk_shipments_tracking', ['carrier_id', 'tracking_no'], 'unique', 'btree'),
    ('idx_shipments_order', ['order_id'], 'btree'),
    ('idx_shipments_status', ['status'], 'hash'),
], checks=[
    'delivered_at IS NULL OR delivered_at >= shipped_at',
])

table('shipping', 'shipment_items', ORANGE, '배송 상품 (부분 배송)', [
    f('id', 'bigint', 'PI', comments='배송 상품 ID'),
    f('shipment_id', 'bigint', comments='배송'),
    f('order_item_id', 'bigint', comments='주문 상품'),
    f('quantity', 'int', check='quantity > 0', comments='배송 수량'),
])

table('shipping', 'shipment_tracking_events', ORANGE, '배송 추적 이력', [
    f('id', 'bigint', 'PI', comments='이력 ID'),
    f('shipment_id', 'bigint', comments='배송'),
    f('status', 'shipment_status', comments='그 시점의 상태'),
    f('location', 'varchar', 'N', len=100, comments='위치'),
    f('description', 'varchar', 'N', len=300, comments='설명'),
    f('occurred_at', 'timestamptz', comments='발생 시각'),
], indexes=[
    ('idx_tracking_events_shipment', ['shipment_id', 'occurred_at'], 'btree'),
])

table('shipping', 'return_requests', ORANGE, '반품/교환 요청', [
    f('id', 'bigint', 'PI', comments='요청 ID'),
    f('shipment_item_id', 'bigint', comments='배송 상품'),
    f('requested_by', 'bigint', comments='요청자'),
    f('return_type', 'return_type', comments='반품/교환'),
    f('status', 'return_status', default="'REQUESTED'", comments='처리 상태'),
    f('reason', 'varchar', len=500, comments='사유'),
    f('quantity', 'int', default='1', check='quantity > 0', comments='수량'),
    f('requested_at', 'timestamp', default='now()', comments='요청 일시'),
    f('completed_at', 'timestamp', 'N', comments='완료 일시'),
])

# ---- report: views -----------------------------------------------------------

table('report', 'v_product_stock', VIEW_COLOR, '상품 옵션별 재고 현황 (뷰)', [
    f('product_option_id', 'bigint', 'N', comments='상품 옵션'),
    f('product_name', 'varchar', 'N', len=200, comments='상품 이름'),
    f('sku', 'varchar', 'N', len=50, comments='재고 관리 코드'),
    f('total_quantity', 'bigint', 'N', comments='전체 보유 수량'),
    f('available_quantity', 'bigint', 'N', comments='주문 가능 수량'),
], view='view')

table('report', 'mv_daily_sales', MVIEW_COLOR, '일별 매출 집계 (구체화 뷰)', [
    f('sales_date', 'date', 'N', comments='매출 일자'),
    f('order_count', 'bigint', 'N', comments='주문 수'),
    f('total_amount', 'numeric', 'N', p=16, s=2, comments='매출 합계'),
    f('average_amount', 'numeric', 'N', p=14, s=2, comments='주문 1건 평균'),
], indexes=[
    ('uk_mv_daily_sales_date', ['sales_date'], 'unique', 'btree'),
], view='materialized')

# ---- relationships -----------------------------------------------------------

RELATIONSHIPS = []


def rel(name, src, tgt, cardinality='many:one'):
    st, sf = src.split('.')
    tt, tf = tgt.split('.')
    s, t = BY_NAME[st], BY_NAME[tt]
    sc, tc = cardinality.split(':')
    RELATIONSHIPS.append({
        'id': nid(),
        'name': name,
        'sourceSchema': s['schema'],
        'sourceTableId': s['id'],
        'targetSchema': t['schema'],
        'targetTableId': t['id'],
        'sourceFieldId': [x['id'] for x in s['fields'] if x['name'] == sf][0],
        'targetFieldId': [x['id'] for x in t['fields'] if x['name'] == tf][0],
        'sourceCardinality': sc,
        'targetCardinality': tc,
        'createdAt': STAMP,
    })


rel('fk_users_grade', 'users.grade_id', 'member_grades.id')
rel('fk_user_profiles_user', 'user_profiles.user_id', 'users.id', 'one:one')
rel('fk_user_addresses_user', 'user_addresses.user_id', 'users.id')
rel('fk_categories_parent', 'categories.parent_id', 'categories.id')
rel('fk_products_category', 'products.category_id', 'categories.id')
rel('fk_products_brand', 'products.brand_id', 'brands.id')
rel('fk_product_options_product', 'product_options.product_id', 'products.id')
# Stored from the one side, as ChartDB does when the line is drawn from the parent.
rel('fk_product_images_product', 'products.id', 'product_images.product_id', 'one:many')
rel('fk_product_tags_product', 'product_tags.product_id', 'products.id')
rel('fk_product_tags_tag', 'product_tags.tag_id', 'tags.id')
rel('fk_product_reviews_product', 'product_reviews.product_id', 'products.id')
rel('fk_product_reviews_user', 'product_reviews.user_id', 'users.id')
rel('fk_warehouses_manager', 'warehouses.manager_id', 'users.id')
rel('fk_stocks_warehouse', 'stocks.warehouse_id', 'warehouses.id')
rel('fk_stocks_product_option', 'stocks.product_option_id', 'product_options.id')
rel('fk_stock_movements_stock', 'stock_movements.stock_id', 'stocks.id')
rel('fk_stock_movements_order', 'stock_movements.order_id', 'orders.id')
rel('fk_stock_movements_created_by', 'stock_movements.created_by', 'users.id')
rel('fk_inbound_orders_supplier', 'inbound_orders.supplier_id', 'suppliers.id')
rel('fk_inbound_orders_warehouse', 'inbound_orders.warehouse_id', 'warehouses.id')
rel('fk_inbound_orders_product_option', 'inbound_orders.product_option_id', 'product_options.id')
rel('fk_orders_user', 'orders.user_id', 'users.id')
rel('fk_order_items_order', 'order_items.order_id', 'orders.id')
rel('fk_order_items_product_option', 'order_items.product_option_id', 'product_options.id')
rel('fk_payments_order', 'payments.order_id', 'orders.id', 'one:one')
rel('promotion_products', 'promotions.id', 'products.id', 'many:many')
rel('fk_shipments_order', 'shipments.order_id', 'orders.id')
rel('fk_shipments_carrier', 'shipments.carrier_id', 'carriers.id')
rel('fk_shipments_warehouse', 'shipments.warehouse_id', 'warehouses.id')
rel('fk_shipment_items_shipment', 'shipment_items.shipment_id', 'shipments.id')
rel('fk_shipment_items_order_item', 'shipment_items.order_item_id', 'order_items.id')
rel('fk_tracking_events_shipment', 'shipment_tracking_events.shipment_id', 'shipments.id')
rel('fk_return_requests_shipment_item', 'return_requests.shipment_item_id', 'shipment_items.id')
rel('fk_return_requests_requested_by', 'return_requests.requested_by', 'users.id')

# ---- view dependencies ---------------------------------------------------------

DEPENDENCIES = []


def depends(view, *tables):
    v = BY_NAME[view]
    for name in tables:
        t = BY_NAME[name]
        DEPENDENCIES.append({
            'id': nid(),
            'schema': t['schema'],
            'tableId': t['id'],
            'dependentSchema': v['schema'],
            'dependentTableId': v['id'],
            'createdAt': STAMP,
        })


depends('v_product_stock', 'products', 'product_options', 'stocks')
depends('mv_daily_sales', 'orders')

# ---- custom types ----------------------------------------------------------------

CUSTOM_TYPES = []


def enum(schema, name, *values):
    CUSTOM_TYPES.append({'id': nid(), 'schema': schema, 'name': name, 'kind': 'enum',
                         'values': list(values), 'order': len(CUSTOM_TYPES)})


def composite(schema, name, *fields):
    CUSTOM_TYPES.append({'id': nid(), 'schema': schema, 'name': name, 'kind': 'composite',
                         'fields': [{'field': a, 'type': b} for a, b in fields], 'order': len(CUSTOM_TYPES)})


enum('member', 'user_role', 'CUSTOMER', 'SELLER', 'ADMIN')
enum('member', 'user_status', 'ACTIVE', 'DORMANT', 'SUSPENDED', 'WITHDRAWN')
enum('catalog', 'product_status', 'DRAFT', 'ON_SALE', 'SOLD_OUT', 'DISCONTINUED')
enum('inventory', 'stock_movement_type', 'INBOUND', 'OUTBOUND', 'ADJUSTMENT', 'RESERVE', 'RELEASE', 'RETURN')
enum('inventory', 'inbound_status', 'REQUESTED', 'IN_TRANSIT', 'RECEIVED', 'CANCELLED')
enum('sales', 'order_status', 'PENDING', 'PAID', 'PREPARING', 'SHIPPED', 'DELIVERED', 'CANCELLED', 'REFUNDED')
enum('sales', 'payment_method', 'CARD', 'BANK_TRANSFER', 'VIRTUAL_ACCOUNT', 'MOBILE', 'POINT')
enum('sales', 'payment_status', 'READY', 'APPROVED', 'FAILED', 'CANCELLED', 'PARTIAL_CANCELLED')
enum('shipping', 'shipment_status', 'READY', 'PICKED_UP', 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'DELIVERED', 'FAILED')
enum('shipping', 'return_type', 'return', 'exchange')
enum('shipping', 'return_status', 'REQUESTED', 'APPROVED', 'COLLECTING', 'COMPLETED', 'REJECTED')
composite('public', 'postal_address',
          ('zipcode', 'varchar(10)'), ('address1', 'varchar(200)'), ('address2', 'varchar(200)'),
          ('city', 'varchar(50)'), ('country_code', 'char(2)'))

# ---- layout: areas, table positions, notes -----------------------------------------

AREAS = []
COLUMN_GAP, ROW_GAP, PAD, HEADER = 60, 60, 40, 70
TABLE_WIDTH = 337


def height(t):
    return 44 + 32 * len(t['fields'])


def area(name, color, x, y, columns, schema):
    members = [t for t in TABLES if t['schema'] == schema]
    area_id = nid()
    column_y = [y + HEADER] * columns
    for i, t in enumerate(members):
        c = column_y.index(min(column_y))
        t['x'] = x + PAD + c * (TABLE_WIDTH + COLUMN_GAP)
        t['y'] = column_y[c]
        t['parentAreaId'] = area_id
        column_y[c] += height(t) + ROW_GAP
    w = PAD * 2 + columns * TABLE_WIDTH + (columns - 1) * COLUMN_GAP
    h = max(column_y) - y - ROW_GAP + PAD
    AREAS.append({'id': area_id, 'name': name, 'x': x, 'y': y, 'width': w, 'height': h, 'color': color,
                  'order': len(AREAS)})
    return x + w, y + h


right, bottom_member = area('사용자 관리 (member)', LILAC, 0, 0, 2, 'member')
right2, bottom_catalog = area('상품 관리 (catalog)', SKY, right + 120, 0, 3, 'catalog')
_, bottom_report = area('현황 (report)', '#b0b0b0', right2 + 120, 0, 1, 'report')
row2 = max(bottom_member, bottom_catalog, bottom_report) + 140
right, _ = area('재고 관리 (inventory)', MINT, 0, row2, 2, 'inventory')
right, _ = area('주문 (sales)', YELLOW, right + 120, row2, 2, 'sales')
area('배송 관리 (shipping)', ORANGE, right + 120, row2, 2, 'shipping')

BY_NAME['products']['expanded'] = True     # shown with every field
BY_NAME['orders']['expanded'] = True
BY_NAME['products']['width'] = 450
BY_NAME['product_tags']['width'] = 224

NOTES = [
    {'id': nid(), 'content': '재고 규칙\n- 주문 접수: stocks.reserved_quantity 증가 (RESERVE)\n- 출고: quantity, reserved_quantity 감소 (OUTBOUND)\n'
                '- 취소: reserved_quantity 감소 (RELEASE)\n- 주문 가능 수량 = quantity - reserved_quantity',
     'x': right2 + 120, 'y': bottom_report + 60, 'width': 417, 'height': 260, 'color': YELLOW, 'order': 0},
    {'id': nid(), 'content': '주문 -> 결제 -> 배송 -> (반품/교환) 흐름. 금액은 numeric, 시각은 감사용만 timestamptz.',
     'x': 0, 'y': row2 - 110, 'width': 620, 'height': 80, 'color': PINK, 'order': 1},
]

DIAGRAM = {
    'id': 'shoperd00001',
    'name': '온라인 쇼핑몰 (Online Shop)',
    'databaseType': 'postgresql',
    'databaseEdition': 'supabase',
    'tables': TABLES,
    'relationships': RELATIONSHIPS,
    'dependencies': DEPENDENCIES,
    'areas': AREAS,
    'customTypes': CUSTOM_TYPES,
    'notes': NOTES,
    'createdAt': '2026-11-05T00:00:00.000Z',
    'updatedAt': '2026-11-05T00:00:00.000Z',
}

# ---- the file, as ChartDB's own export writes one ------------------------------------

TABLE_KEYS = ['id', 'name', 'schema', 'x', 'y', 'fields', 'indexes', 'checkConstraints', 'color', 'isView',
              'isMaterializedView', 'createdAt', 'width', 'comments', 'order', 'expanded', 'parentAreaId']
FIELD_KEYS = ['id', 'name', 'type', 'primaryKey', 'unique', 'nullable', 'increment', 'isArray', 'createdAt',
              'characterMaximumLength', 'precision', 'scale', 'default', 'collation', 'comments', 'check']
INDEX_KEYS = ['id', 'name', 'unique', 'fieldIds', 'createdAt', 'type', 'isPrimaryKey', 'comments']


def ordered(element, keys):
    return dict((k, element[k]) for k in keys if k in element)


def canonical(diagram):
    """Ids as ChartDB numbers them on export (clone.ts): each table with its fields and indexes, then
    relationships, dependencies, areas, notes, custom types. Keys in the order of the interfaces."""
    ids = {}
    n = [0]

    def renumber(element):
        n[0] += 1
        ids[element['id']] = str(n[0])

    for t in diagram['tables']:
        renumber(t)
        for x in t['fields']:
            renumber(x)
        for x in t['indexes']:
            renumber(x)
    for key in ('relationships', 'dependencies', 'areas', 'notes', 'customTypes'):
        for x in diagram[key]:
            renumber(x)
    for t in diagram['tables']:
        t['id'] = ids[t['id']]
        t['parentAreaId'] = ids[t['parentAreaId']]
        t['fields'] = [ordered(dict(x, id=ids[x['id']]), FIELD_KEYS) for x in t['fields']]
        t['indexes'] = [ordered(dict(x, id=ids[x['id']], fieldIds=[ids[i] for i in x['fieldIds']],
                                     name='' if x.get('isPrimaryKey') else x['name']), INDEX_KEYS)
                        for x in t['indexes']]
        for i, c in enumerate(t.get('checkConstraints', [])):
            c['id'] = '%s-check-%d' % (t['id'], i + 1)
    diagram['tables'] = [ordered(t, TABLE_KEYS) for t in diagram['tables']]
    for r in diagram['relationships']:
        for k in ('id', 'sourceTableId', 'targetTableId', 'sourceFieldId', 'targetFieldId'):
            r[k] = ids[r[k]]
    for d in diagram['dependencies']:
        for k in ('id', 'tableId', 'dependentTableId'):
            d[k] = ids[d[k]]
    for key in ('areas', 'notes', 'customTypes'):
        for x in diagram[key]:
            x['id'] = ids[x['id']]
    return diagram


if __name__ == '__main__':
    target = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'shop_chartdb.json')
    with io.open(target, 'w', encoding='utf-8', newline=chr(10)) as out:
        out.write(json.dumps(canonical(DIAGRAM), ensure_ascii=False, indent=2))
    print('%s: %d tables, %d relationships, %d dependencies, %d areas, %d custom types, %d notes' % (
        target, len(TABLES), len(RELATIONSHIPS), len(DEPENDENCIES), len(AREAS), len(CUSTOM_TYPES), len(NOTES)))
