CREATE SCHEMA IF NOT EXISTS "member";
CREATE SCHEMA IF NOT EXISTS "catalog";
CREATE SCHEMA IF NOT EXISTS "inventory";
CREATE SCHEMA IF NOT EXISTS "sales";
CREATE SCHEMA IF NOT EXISTS "shipping";
CREATE SCHEMA IF NOT EXISTS "report";
CREATE SCHEMA IF NOT EXISTS "public";

CREATE TYPE "inventory"."inbound_status" AS ENUM ('REQUESTED', 'IN_TRANSIT', 'RECEIVED', 'CANCELLED');
CREATE TYPE "sales"."order_status" AS ENUM ('PENDING', 'PAID', 'PREPARING', 'SHIPPED', 'DELIVERED', 'CANCELLED', 'REFUNDED');
CREATE TYPE "sales"."payment_method" AS ENUM ('CARD', 'BANK_TRANSFER', 'VIRTUAL_ACCOUNT', 'MOBILE', 'POINT');
CREATE TYPE "sales"."payment_status" AS ENUM ('READY', 'APPROVED', 'FAILED', 'CANCELLED', 'PARTIAL_CANCELLED');
CREATE TYPE "catalog"."product_status" AS ENUM ('DRAFT', 'ON_SALE', 'SOLD_OUT', 'DISCONTINUED');
CREATE TYPE "shipping"."return_status" AS ENUM ('REQUESTED', 'APPROVED', 'COLLECTING', 'COMPLETED', 'REJECTED');
CREATE TYPE "shipping"."return_type" AS ENUM ('return', 'exchange');
CREATE TYPE "shipping"."shipment_status" AS ENUM ('READY', 'PICKED_UP', 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'DELIVERED', 'FAILED');
CREATE TYPE "inventory"."stock_movement_type" AS ENUM ('INBOUND', 'OUTBOUND', 'ADJUSTMENT', 'RESERVE', 'RELEASE', 'RETURN');
CREATE TYPE "member"."user_role" AS ENUM ('CUSTOMER', 'SELLER', 'ADMIN');
CREATE TYPE "member"."user_status" AS ENUM ('ACTIVE', 'DORMANT', 'SUSPENDED', 'WITHDRAWN');
CREATE TYPE "public"."postal_address" AS ("zipcode" varchar(10), "address1" varchar(200), "address2" varchar(200), "city" varchar(50), "country_code" char(2));

-- 회원 등급
CREATE TABLE "member"."member_grades" (
    -- 등급 ID
    "id" bigserial NOT NULL,
    -- 등급 코드 (BRONZE, SILVER ...)
    "code" varchar(20) NOT NULL UNIQUE,
    -- 등급 이름
    "name" varchar(50) NOT NULL,
    -- 할인율 (%)
    "discount_rate" numeric(5, 2) NOT NULL DEFAULT 0,
    -- 등급 기준 누적 구매액
    "min_purchase_amount" numeric(12, 2) NOT NULL DEFAULT 0,
    PRIMARY KEY ("id")
);
COMMENT ON TABLE "member"."member_grades" IS '회원 등급';
COMMENT ON COLUMN "member"."member_grades"."id" IS '등급 ID';
COMMENT ON COLUMN "member"."member_grades"."code" IS '등급 코드 (BRONZE, SILVER ...)';
COMMENT ON COLUMN "member"."member_grades"."name" IS '등급 이름';
COMMENT ON COLUMN "member"."member_grades"."discount_rate" IS '할인율 (%)';
COMMENT ON COLUMN "member"."member_grades"."min_purchase_amount" IS '등급 기준 누적 구매액';

-- 사용자 (고객과 관리자)
CREATE TABLE "member"."users" (
    -- 사용자 ID
    "id" bigserial NOT NULL,
    -- 로그인 아이디
    "login_id" varchar(30) NOT NULL UNIQUE,
    -- 비밀번호 해시
    "password_hash" varchar(255) NOT NULL,
    -- 이름
    "name" varchar(50) NOT NULL,
    -- 이메일
    "email" varchar(100) NOT NULL,
    -- 휴대전화
    "phone" varchar(20),
    -- 생년월일
    "birth_date" date,
    -- 권한
    "role" user_role NOT NULL DEFAULT 'CUSTOMER',
    -- 계정 상태
    "status" user_status NOT NULL DEFAULT 'ACTIVE',
    -- 회원 등급
    "grade_id" bigint,
    -- 마케팅 수신 동의
    "marketing_agreed" boolean NOT NULL DEFAULT false,
    -- 마지막 로그인 시각
    "last_login_at" timestamptz,
    -- 마지막 로그인 IP
    "last_login_ip" inet,
    -- 가입 일시
    "created_at" timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- 수정 일시
    "updated_at" timestamp,
    PRIMARY KEY ("id")
);
COMMENT ON TABLE "member"."users" IS '사용자 (고객과 관리자)';
COMMENT ON COLUMN "member"."users"."id" IS '사용자 ID';
COMMENT ON COLUMN "member"."users"."login_id" IS '로그인 아이디';
COMMENT ON COLUMN "member"."users"."password_hash" IS '비밀번호 해시';
COMMENT ON COLUMN "member"."users"."name" IS '이름';
COMMENT ON COLUMN "member"."users"."email" IS '이메일';
COMMENT ON COLUMN "member"."users"."phone" IS '휴대전화';
COMMENT ON COLUMN "member"."users"."birth_date" IS '생년월일';
COMMENT ON COLUMN "member"."users"."role" IS '권한';
COMMENT ON COLUMN "member"."users"."status" IS '계정 상태';
COMMENT ON COLUMN "member"."users"."grade_id" IS '회원 등급';
COMMENT ON COLUMN "member"."users"."marketing_agreed" IS '마케팅 수신 동의';
COMMENT ON COLUMN "member"."users"."last_login_at" IS '마지막 로그인 시각';
COMMENT ON COLUMN "member"."users"."last_login_ip" IS '마지막 로그인 IP';
COMMENT ON COLUMN "member"."users"."created_at" IS '가입 일시';
COMMENT ON COLUMN "member"."users"."updated_at" IS '수정 일시';
-- Indexes
CREATE INDEX "users_idx_users_grade" ON "member"."users" USING HASH ("grade_id");
CREATE INDEX "users_idx_users_status_role" ON "member"."users" ("status", "role");
CREATE UNIQUE INDEX "users_uk_users_email" ON "member"."users" ("email");
COMMENT ON INDEX "users_uk_users_email" IS '이메일은 한 사람에 하나';

-- 사용자 프로필 (사용자와 1:1)
CREATE TABLE "member"."user_profiles" (
    -- 사용자 ID
    "user_id" bigint NOT NULL,
    -- 별명
    "nickname" varchar(30) UNIQUE,
    -- 프로필 이미지
    "profile_image_url" varchar(500),
    -- 자기소개
    "bio" text,
    -- 관심 분야
    "interests" text[],
    -- 화면 설정
    "preferences" jsonb DEFAULT '{}',
    PRIMARY KEY ("user_id")
);
COMMENT ON TABLE "member"."user_profiles" IS '사용자 프로필 (사용자와 1:1)';
COMMENT ON COLUMN "member"."user_profiles"."user_id" IS '사용자 ID';
COMMENT ON COLUMN "member"."user_profiles"."nickname" IS '별명';
COMMENT ON COLUMN "member"."user_profiles"."profile_image_url" IS '프로필 이미지';
COMMENT ON COLUMN "member"."user_profiles"."bio" IS '자기소개';
COMMENT ON COLUMN "member"."user_profiles"."interests" IS '관심 분야';
COMMENT ON COLUMN "member"."user_profiles"."preferences" IS '화면 설정';

-- 배송지 주소록
CREATE TABLE "member"."user_addresses" (
    -- 주소 ID
    "id" bigserial NOT NULL,
    -- 사용자
    "user_id" bigint NOT NULL,
    -- 주소 이름 (집, 회사)
    "label" varchar(30) NOT NULL DEFAULT '집',
    -- 받는 사람
    "recipient" varchar(50) NOT NULL,
    -- 연락처
    "phone" varchar(20) NOT NULL,
    -- 주소
    "address" postal_address NOT NULL,
    -- 기본 배송지
    "is_default" boolean NOT NULL DEFAULT false,
    -- 배송 요청 사항
    "delivery_memo" varchar(200),
    PRIMARY KEY ("id")
);
COMMENT ON TABLE "member"."user_addresses" IS '배송지 주소록';
COMMENT ON COLUMN "member"."user_addresses"."id" IS '주소 ID';
COMMENT ON COLUMN "member"."user_addresses"."user_id" IS '사용자';
COMMENT ON COLUMN "member"."user_addresses"."label" IS '주소 이름 (집, 회사)';
COMMENT ON COLUMN "member"."user_addresses"."recipient" IS '받는 사람';
COMMENT ON COLUMN "member"."user_addresses"."phone" IS '연락처';
COMMENT ON COLUMN "member"."user_addresses"."address" IS '주소';
COMMENT ON COLUMN "member"."user_addresses"."is_default" IS '기본 배송지';
COMMENT ON COLUMN "member"."user_addresses"."delivery_memo" IS '배송 요청 사항';
-- Indexes
CREATE INDEX "user_addresses_idx_user_addresses_user" ON "member"."user_addresses" ("user_id", "is_default");

-- 상품 분류 (계층)
CREATE TABLE "catalog"."categories" (
    -- 분류 ID
    "id" bigserial NOT NULL,
    -- 상위 분류
    "parent_id" bigint,
    -- 분류 이름
    "name" varchar(100) NOT NULL,
    -- 깊이 (1 = 대분류)
    "depth" smallint NOT NULL DEFAULT 1,
    -- 정렬 순서
    "sort_order" int NOT NULL DEFAULT 0,
    -- 노출 여부
    "is_visible" boolean NOT NULL DEFAULT true,
    PRIMARY KEY ("id")
);
COMMENT ON TABLE "catalog"."categories" IS '상품 분류 (계층)';
COMMENT ON COLUMN "catalog"."categories"."id" IS '분류 ID';
COMMENT ON COLUMN "catalog"."categories"."parent_id" IS '상위 분류';
COMMENT ON COLUMN "catalog"."categories"."name" IS '분류 이름';
COMMENT ON COLUMN "catalog"."categories"."depth" IS '깊이 (1 = 대분류)';
COMMENT ON COLUMN "catalog"."categories"."sort_order" IS '정렬 순서';
COMMENT ON COLUMN "catalog"."categories"."is_visible" IS '노출 여부';
-- Indexes
CREATE INDEX "categories_idx_categories_parent" ON "catalog"."categories" ("parent_id", "sort_order");

-- 브랜드
CREATE TABLE "catalog"."brands" (
    -- 브랜드 ID
    "id" serial NOT NULL,
    -- 브랜드 이름
    "name" varchar(100) NOT NULL UNIQUE,
    -- 로고 이미지
    "logo_url" varchar(500),
    -- 국가 코드 (ISO 3166-1)
    "country_code" char(2),
    PRIMARY KEY ("id")
);
COMMENT ON TABLE "catalog"."brands" IS '브랜드';
COMMENT ON COLUMN "catalog"."brands"."id" IS '브랜드 ID';
COMMENT ON COLUMN "catalog"."brands"."name" IS '브랜드 이름';
COMMENT ON COLUMN "catalog"."brands"."logo_url" IS '로고 이미지';
COMMENT ON COLUMN "catalog"."brands"."country_code" IS '국가 코드 (ISO 3166-1)';

-- 상품
CREATE TABLE "catalog"."products" (
    -- 상품 ID
    "id" bigserial NOT NULL,
    -- 분류
    "category_id" bigint NOT NULL,
    -- 브랜드
    "brand_id" int,
    -- 상품 코드
    "code" varchar(40) NOT NULL UNIQUE,
    -- 상품 이름
    "name" varchar(200) NOT NULL,
    -- 상세 설명
    "description" text,
    -- 판매 상태
    "status" product_status NOT NULL DEFAULT 'DRAFT',
    -- 정가
    "list_price" numeric(12, 2) NOT NULL,
    -- 판매가
    "sale_price" numeric(12, 2) NOT NULL,
    -- 세율 (%)
    "tax_rate" numeric(5, 2) NOT NULL DEFAULT 10.00,
    -- 무게 (kg)
    "weight_kg" real,
    -- 검색 키워드
    "keywords" varchar(50)[],
    -- 상품 속성 (색상, 소재 ...)
    "attributes" jsonb,
    -- 전문 검색 벡터
    "search_vector" tsvector,
    -- 판매 시작
    "sale_start_at" timestamp,
    -- 판매 종료
    "sale_end_at" timestamp,
    -- 등록 일시
    "created_at" timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- 수정 일시
    "updated_at" timestamp,
    PRIMARY KEY ("id"),
    CHECK (list_price >= 0),
    CHECK (sale_price >= 0 AND sale_price <= list_price),
    CHECK (sale_end_at IS NULL OR sale_end_at > sale_start_at)
);
COMMENT ON TABLE "catalog"."products" IS '상품';
COMMENT ON COLUMN "catalog"."products"."id" IS '상품 ID';
COMMENT ON COLUMN "catalog"."products"."category_id" IS '분류';
COMMENT ON COLUMN "catalog"."products"."brand_id" IS '브랜드';
COMMENT ON COLUMN "catalog"."products"."code" IS '상품 코드';
COMMENT ON COLUMN "catalog"."products"."name" IS '상품 이름';
COMMENT ON COLUMN "catalog"."products"."description" IS '상세 설명';
COMMENT ON COLUMN "catalog"."products"."status" IS '판매 상태';
COMMENT ON COLUMN "catalog"."products"."list_price" IS '정가';
COMMENT ON COLUMN "catalog"."products"."sale_price" IS '판매가';
COMMENT ON COLUMN "catalog"."products"."tax_rate" IS '세율 (%)';
COMMENT ON COLUMN "catalog"."products"."weight_kg" IS '무게 (kg)';
COMMENT ON COLUMN "catalog"."products"."keywords" IS '검색 키워드';
COMMENT ON COLUMN "catalog"."products"."attributes" IS '상품 속성 (색상, 소재 ...)';
COMMENT ON COLUMN "catalog"."products"."search_vector" IS '전문 검색 벡터';
COMMENT ON COLUMN "catalog"."products"."sale_start_at" IS '판매 시작';
COMMENT ON COLUMN "catalog"."products"."sale_end_at" IS '판매 종료';
COMMENT ON COLUMN "catalog"."products"."created_at" IS '등록 일시';
COMMENT ON COLUMN "catalog"."products"."updated_at" IS '수정 일시';
-- Indexes
CREATE INDEX "products_idx_products_attributes" ON "catalog"."products" USING GIN ("attributes");
CREATE INDEX "products_idx_products_brand" ON "catalog"."products" ("brand_id");
CREATE INDEX "products_idx_products_category_status" ON "catalog"."products" ("category_id", "status");
CREATE INDEX "products_idx_products_keywords" ON "catalog"."products" USING GIN ("keywords");
COMMENT ON INDEX "products_idx_products_keywords" IS '키워드 배열 검색';

-- 상품 옵션 (재고 단위, SKU)
CREATE TABLE "catalog"."product_options" (
    -- 옵션 ID
    "id" bigserial NOT NULL,
    -- 상품
    "product_id" bigint NOT NULL,
    -- 재고 관리 코드
    "sku" varchar(50) NOT NULL UNIQUE,
    -- 옵션 이름 (색상/사이즈)
    "option_name" varchar(100) NOT NULL,
    -- 옵션 값 (블랙/270)
    "option_value" varchar(100) NOT NULL,
    -- 추가 금액
    "additional_price" numeric(12, 2) NOT NULL DEFAULT 0,
    -- 바코드
    "barcode" varchar(30),
    -- 판매 여부
    "is_active" boolean NOT NULL DEFAULT true,
    PRIMARY KEY ("id")
);
COMMENT ON TABLE "catalog"."product_options" IS '상품 옵션 (재고 단위, SKU)';
COMMENT ON COLUMN "catalog"."product_options"."id" IS '옵션 ID';
COMMENT ON COLUMN "catalog"."product_options"."product_id" IS '상품';
COMMENT ON COLUMN "catalog"."product_options"."sku" IS '재고 관리 코드';
COMMENT ON COLUMN "catalog"."product_options"."option_name" IS '옵션 이름 (색상/사이즈)';
COMMENT ON COLUMN "catalog"."product_options"."option_value" IS '옵션 값 (블랙/270)';
COMMENT ON COLUMN "catalog"."product_options"."additional_price" IS '추가 금액';
COMMENT ON COLUMN "catalog"."product_options"."barcode" IS '바코드';
COMMENT ON COLUMN "catalog"."product_options"."is_active" IS '판매 여부';
-- Indexes
CREATE UNIQUE INDEX "product_options_uk_product_options_value" ON "catalog"."product_options" ("product_id", "option_name", "option_value");

-- 상품 이미지
CREATE TABLE "catalog"."product_images" (
    -- 이미지 ID
    "id" bigserial NOT NULL,
    -- 상품
    "product_id" bigint NOT NULL,
    -- 이미지 주소
    "url" varchar(500) NOT NULL,
    -- 대체 텍스트
    "alt_text" varchar(200),
    -- 정렬 순서
    "sort_order" int NOT NULL DEFAULT 0,
    -- 대표 이미지
    "is_main" boolean NOT NULL DEFAULT false,
    -- 썸네일 원본
    "thumbnail" bytea,
    PRIMARY KEY ("id")
);
COMMENT ON TABLE "catalog"."product_images" IS '상품 이미지';
COMMENT ON COLUMN "catalog"."product_images"."id" IS '이미지 ID';
COMMENT ON COLUMN "catalog"."product_images"."product_id" IS '상품';
COMMENT ON COLUMN "catalog"."product_images"."url" IS '이미지 주소';
COMMENT ON COLUMN "catalog"."product_images"."alt_text" IS '대체 텍스트';
COMMENT ON COLUMN "catalog"."product_images"."sort_order" IS '정렬 순서';
COMMENT ON COLUMN "catalog"."product_images"."is_main" IS '대표 이미지';
COMMENT ON COLUMN "catalog"."product_images"."thumbnail" IS '썸네일 원본';

-- 태그
CREATE TABLE "catalog"."tags" (
    -- 태그 ID
    "id" bigserial NOT NULL,
    -- 태그 이름
    "name" varchar(50) NOT NULL UNIQUE,
    PRIMARY KEY ("id")
);
COMMENT ON TABLE "catalog"."tags" IS '태그';
COMMENT ON COLUMN "catalog"."tags"."id" IS '태그 ID';
COMMENT ON COLUMN "catalog"."tags"."name" IS '태그 이름';

-- 상품-태그 연결 (복합 키)
CREATE TABLE "catalog"."product_tags" (
    -- 상품
    "product_id" bigint NOT NULL,
    -- 태그
    "tag_id" bigint NOT NULL,
    -- 연결 일시
    "tagged_at" timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY ("product_id", "tag_id")
);
COMMENT ON TABLE "catalog"."product_tags" IS '상품-태그 연결 (복합 키)';
COMMENT ON COLUMN "catalog"."product_tags"."product_id" IS '상품';
COMMENT ON COLUMN "catalog"."product_tags"."tag_id" IS '태그';
COMMENT ON COLUMN "catalog"."product_tags"."tagged_at" IS '연결 일시';

-- 상품 후기
CREATE TABLE "catalog"."product_reviews" (
    -- 후기 ID
    "id" bigserial NOT NULL,
    -- 상품
    "product_id" bigint NOT NULL,
    -- 작성자
    "user_id" bigint NOT NULL,
    -- 별점 (1-5)
    "rating" smallint NOT NULL,
    -- 제목
    "title" varchar(200),
    -- 내용
    "content" text NOT NULL,
    -- 숨김 여부
    "is_hidden" boolean NOT NULL DEFAULT false,
    -- 작성 일시
    "created_at" timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY ("id")
);
COMMENT ON TABLE "catalog"."product_reviews" IS '상품 후기';
COMMENT ON COLUMN "catalog"."product_reviews"."id" IS '후기 ID';
COMMENT ON COLUMN "catalog"."product_reviews"."product_id" IS '상품';
COMMENT ON COLUMN "catalog"."product_reviews"."user_id" IS '작성자';
COMMENT ON COLUMN "catalog"."product_reviews"."rating" IS '별점 (1-5)';
COMMENT ON COLUMN "catalog"."product_reviews"."title" IS '제목';
COMMENT ON COLUMN "catalog"."product_reviews"."content" IS '내용';
COMMENT ON COLUMN "catalog"."product_reviews"."is_hidden" IS '숨김 여부';
COMMENT ON COLUMN "catalog"."product_reviews"."created_at" IS '작성 일시';
-- Indexes
CREATE UNIQUE INDEX "product_reviews_uk_product_reviews_user" ON "catalog"."product_reviews" ("product_id", "user_id");
COMMENT ON INDEX "product_reviews_uk_product_reviews_user" IS '상품마다 한 사람이 한 번';

-- 창고
CREATE TABLE "inventory"."warehouses" (
    -- 창고 ID
    "id" bigserial NOT NULL,
    -- 창고 코드
    "code" varchar(20) NOT NULL UNIQUE,
    -- 창고 이름
    "name" varchar(100) NOT NULL,
    -- 창고 주소
    "address" postal_address,
    -- 담당자
    "manager_id" bigint,
    -- 운영 시작 시각
    "opens_at" time,
    -- 운영 종료 시각
    "closes_at" time,
    -- 운영 여부
    "is_active" boolean NOT NULL DEFAULT true,
    PRIMARY KEY ("id")
);
COMMENT ON TABLE "inventory"."warehouses" IS '창고';
COMMENT ON COLUMN "inventory"."warehouses"."id" IS '창고 ID';
COMMENT ON COLUMN "inventory"."warehouses"."code" IS '창고 코드';
COMMENT ON COLUMN "inventory"."warehouses"."name" IS '창고 이름';
COMMENT ON COLUMN "inventory"."warehouses"."address" IS '창고 주소';
COMMENT ON COLUMN "inventory"."warehouses"."manager_id" IS '담당자';
COMMENT ON COLUMN "inventory"."warehouses"."opens_at" IS '운영 시작 시각';
COMMENT ON COLUMN "inventory"."warehouses"."closes_at" IS '운영 종료 시각';
COMMENT ON COLUMN "inventory"."warehouses"."is_active" IS '운영 여부';

-- 공급사
CREATE TABLE "inventory"."suppliers" (
    -- 공급사 ID
    "id" bigserial NOT NULL,
    -- 공급사 이름
    "name" varchar(100) NOT NULL,
    -- 사업자 등록 번호
    "business_no" varchar(12) NOT NULL UNIQUE,
    -- 담당자
    "contact_name" varchar(50),
    -- 담당자 연락처
    "contact_phone" varchar(20),
    -- 납품 소요 일수
    "lead_time_days" int NOT NULL DEFAULT 3,
    PRIMARY KEY ("id")
);
COMMENT ON TABLE "inventory"."suppliers" IS '공급사';
COMMENT ON COLUMN "inventory"."suppliers"."id" IS '공급사 ID';
COMMENT ON COLUMN "inventory"."suppliers"."name" IS '공급사 이름';
COMMENT ON COLUMN "inventory"."suppliers"."business_no" IS '사업자 등록 번호';
COMMENT ON COLUMN "inventory"."suppliers"."contact_name" IS '담당자';
COMMENT ON COLUMN "inventory"."suppliers"."contact_phone" IS '담당자 연락처';
COMMENT ON COLUMN "inventory"."suppliers"."lead_time_days" IS '납품 소요 일수';

-- 재고 (창고 x 옵션)
CREATE TABLE "inventory"."stocks" (
    -- 재고 ID
    "id" bigserial NOT NULL,
    -- 창고
    "warehouse_id" bigint NOT NULL,
    -- 상품 옵션
    "product_option_id" bigint NOT NULL,
    -- 보유 수량
    "quantity" int NOT NULL DEFAULT 0,
    -- 주문으로 잡힌 수량
    "reserved_quantity" int NOT NULL DEFAULT 0,
    -- 안전 재고
    "safety_stock" int NOT NULL DEFAULT 0,
    -- 보관 위치 (A-01-03)
    "location" varchar(30),
    -- 낙관적 잠금
    "version" bigint NOT NULL DEFAULT 0,
    -- 수정 일시
    "updated_at" timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY ("id"),
    CHECK (reserved_quantity >= 0 AND reserved_quantity <= quantity)
);
COMMENT ON TABLE "inventory"."stocks" IS '재고 (창고 x 옵션)';
COMMENT ON COLUMN "inventory"."stocks"."id" IS '재고 ID';
COMMENT ON COLUMN "inventory"."stocks"."warehouse_id" IS '창고';
COMMENT ON COLUMN "inventory"."stocks"."product_option_id" IS '상품 옵션';
COMMENT ON COLUMN "inventory"."stocks"."quantity" IS '보유 수량';
COMMENT ON COLUMN "inventory"."stocks"."reserved_quantity" IS '주문으로 잡힌 수량';
COMMENT ON COLUMN "inventory"."stocks"."safety_stock" IS '안전 재고';
COMMENT ON COLUMN "inventory"."stocks"."location" IS '보관 위치 (A-01-03)';
COMMENT ON COLUMN "inventory"."stocks"."version" IS '낙관적 잠금';
COMMENT ON COLUMN "inventory"."stocks"."updated_at" IS '수정 일시';
-- Indexes
CREATE UNIQUE INDEX "stocks_uk_stocks_warehouse_option" ON "inventory"."stocks" ("warehouse_id", "product_option_id");

-- 재고 이동 이력
CREATE TABLE "inventory"."stock_movements" (
    -- 이동 ID
    "id" uuid NOT NULL DEFAULT 'gen_random_uuid()',
    -- 재고
    "stock_id" bigint NOT NULL,
    -- 이동 종류
    "movement_type" stock_movement_type NOT NULL,
    -- 변화량 (입고 +, 출고 -)
    "quantity" int NOT NULL,
    -- 사유
    "reason" varchar(200),
    -- 관련 주문
    "order_id" bigint,
    -- 처리자
    "created_by" bigint,
    -- 발생 시각
    "created_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY ("id")
);
COMMENT ON TABLE "inventory"."stock_movements" IS '재고 이동 이력';
COMMENT ON COLUMN "inventory"."stock_movements"."id" IS '이동 ID';
COMMENT ON COLUMN "inventory"."stock_movements"."stock_id" IS '재고';
COMMENT ON COLUMN "inventory"."stock_movements"."movement_type" IS '이동 종류';
COMMENT ON COLUMN "inventory"."stock_movements"."quantity" IS '변화량 (입고 +, 출고 -)';
COMMENT ON COLUMN "inventory"."stock_movements"."reason" IS '사유';
COMMENT ON COLUMN "inventory"."stock_movements"."order_id" IS '관련 주문';
COMMENT ON COLUMN "inventory"."stock_movements"."created_by" IS '처리자';
COMMENT ON COLUMN "inventory"."stock_movements"."created_at" IS '발생 시각';
-- Indexes
CREATE INDEX "stock_movements_idx_stock_movements_stock" ON "inventory"."stock_movements" ("stock_id", "created_at");

-- 입고 예정 (발주)
CREATE TABLE "inventory"."inbound_orders" (
    -- 발주 ID
    "id" bigserial NOT NULL,
    -- 공급사
    "supplier_id" bigint NOT NULL,
    -- 입고 창고
    "warehouse_id" bigint NOT NULL,
    -- 상품 옵션
    "product_option_id" bigint NOT NULL,
    -- 발주 수량
    "quantity" int NOT NULL,
    -- 매입 단가
    "unit_cost" numeric(12, 2) NOT NULL,
    -- 진행 상태
    "status" inbound_status NOT NULL DEFAULT 'REQUESTED',
    -- 입고 예정일
    "expected_date" date,
    -- 입고 일시
    "received_at" timestamp,
    PRIMARY KEY ("id")
);
COMMENT ON TABLE "inventory"."inbound_orders" IS '입고 예정 (발주)';
COMMENT ON COLUMN "inventory"."inbound_orders"."id" IS '발주 ID';
COMMENT ON COLUMN "inventory"."inbound_orders"."supplier_id" IS '공급사';
COMMENT ON COLUMN "inventory"."inbound_orders"."warehouse_id" IS '입고 창고';
COMMENT ON COLUMN "inventory"."inbound_orders"."product_option_id" IS '상품 옵션';
COMMENT ON COLUMN "inventory"."inbound_orders"."quantity" IS '발주 수량';
COMMENT ON COLUMN "inventory"."inbound_orders"."unit_cost" IS '매입 단가';
COMMENT ON COLUMN "inventory"."inbound_orders"."status" IS '진행 상태';
COMMENT ON COLUMN "inventory"."inbound_orders"."expected_date" IS '입고 예정일';
COMMENT ON COLUMN "inventory"."inbound_orders"."received_at" IS '입고 일시';

-- 주문
CREATE TABLE "sales"."orders" (
    -- 주문 ID
    "id" bigserial NOT NULL,
    -- 주문 번호
    "order_no" varchar(30) NOT NULL UNIQUE,
    -- 주문자
    "user_id" bigint NOT NULL,
    -- 주문 상태
    "status" order_status NOT NULL DEFAULT 'PENDING',
    -- 상품 금액 합계
    "total_amount" numeric(14, 2) NOT NULL DEFAULT 0,
    -- 할인 금액
    "discount_amount" numeric(14, 2) NOT NULL DEFAULT 0,
    -- 배송비
    "shipping_fee" numeric(10, 2) NOT NULL DEFAULT 0,
    -- 결제 금액
    "payment_amount" numeric(14, 2) NOT NULL DEFAULT 0,
    -- 배송지
    "shipping_address" postal_address NOT NULL,
    -- 받는 사람
    "recipient" varchar(50) NOT NULL,
    -- 받는 사람 연락처
    "recipient_phone" varchar(20) NOT NULL,
    -- 주문 메모
    "memo" varchar(500),
    -- 주문 일시
    "ordered_at" timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- 취소 일시
    "cancelled_at" timestamp,
    PRIMARY KEY ("id"),
    CHECK (payment_amount = total_amount - discount_amount + shipping_fee)
);
COMMENT ON TABLE "sales"."orders" IS '주문';
COMMENT ON COLUMN "sales"."orders"."id" IS '주문 ID';
COMMENT ON COLUMN "sales"."orders"."order_no" IS '주문 번호';
COMMENT ON COLUMN "sales"."orders"."user_id" IS '주문자';
COMMENT ON COLUMN "sales"."orders"."status" IS '주문 상태';
COMMENT ON COLUMN "sales"."orders"."total_amount" IS '상품 금액 합계';
COMMENT ON COLUMN "sales"."orders"."discount_amount" IS '할인 금액';
COMMENT ON COLUMN "sales"."orders"."shipping_fee" IS '배송비';
COMMENT ON COLUMN "sales"."orders"."payment_amount" IS '결제 금액';
COMMENT ON COLUMN "sales"."orders"."shipping_address" IS '배송지';
COMMENT ON COLUMN "sales"."orders"."recipient" IS '받는 사람';
COMMENT ON COLUMN "sales"."orders"."recipient_phone" IS '받는 사람 연락처';
COMMENT ON COLUMN "sales"."orders"."memo" IS '주문 메모';
COMMENT ON COLUMN "sales"."orders"."ordered_at" IS '주문 일시';
COMMENT ON COLUMN "sales"."orders"."cancelled_at" IS '취소 일시';
-- Indexes
CREATE INDEX "orders_idx_orders_status" ON "sales"."orders" ("status");
CREATE INDEX "orders_idx_orders_user_ordered" ON "sales"."orders" ("user_id", "ordered_at");

-- 주문 상품
CREATE TABLE "sales"."order_items" (
    -- 주문 상품 ID
    "id" bigserial NOT NULL,
    -- 주문
    "order_id" bigint NOT NULL,
    -- 상품 옵션
    "product_option_id" bigint NOT NULL,
    -- 주문 당시 상품 이름
    "product_name" varchar(200) NOT NULL,
    -- 주문 당시 단가
    "unit_price" numeric(12, 2) NOT NULL,
    -- 수량
    "quantity" int NOT NULL,
    -- 소계
    "subtotal" numeric(14, 2) NOT NULL,
    PRIMARY KEY ("id")
);
COMMENT ON TABLE "sales"."order_items" IS '주문 상품';
COMMENT ON COLUMN "sales"."order_items"."id" IS '주문 상품 ID';
COMMENT ON COLUMN "sales"."order_items"."order_id" IS '주문';
COMMENT ON COLUMN "sales"."order_items"."product_option_id" IS '상품 옵션';
COMMENT ON COLUMN "sales"."order_items"."product_name" IS '주문 당시 상품 이름';
COMMENT ON COLUMN "sales"."order_items"."unit_price" IS '주문 당시 단가';
COMMENT ON COLUMN "sales"."order_items"."quantity" IS '수량';
COMMENT ON COLUMN "sales"."order_items"."subtotal" IS '소계';
-- Indexes
CREATE INDEX "order_items_idx_order_items_order" ON "sales"."order_items" ("order_id");

-- 결제 (주문과 1:1)
CREATE TABLE "sales"."payments" (
    -- 결제 ID
    "id" bigserial NOT NULL,
    -- 주문
    "order_id" bigint NOT NULL UNIQUE,
    -- 결제 수단
    "method" payment_method NOT NULL,
    -- 결제 금액
    "amount" numeric(14, 2) NOT NULL,
    -- 결제 상태
    "status" payment_status NOT NULL DEFAULT 'READY',
    -- PG 거래 번호
    "pg_transaction_id" varchar(100) UNIQUE,
    -- 결제 승인 시각
    "paid_at" timestamptz,
    -- PG 응답 원문
    "raw_response" json,
    PRIMARY KEY ("id")
);
COMMENT ON TABLE "sales"."payments" IS '결제 (주문과 1:1)';
COMMENT ON COLUMN "sales"."payments"."id" IS '결제 ID';
COMMENT ON COLUMN "sales"."payments"."order_id" IS '주문';
COMMENT ON COLUMN "sales"."payments"."method" IS '결제 수단';
COMMENT ON COLUMN "sales"."payments"."amount" IS '결제 금액';
COMMENT ON COLUMN "sales"."payments"."status" IS '결제 상태';
COMMENT ON COLUMN "sales"."payments"."pg_transaction_id" IS 'PG 거래 번호';
COMMENT ON COLUMN "sales"."payments"."paid_at" IS '결제 승인 시각';
COMMENT ON COLUMN "sales"."payments"."raw_response" IS 'PG 응답 원문';

-- 프로모션 (상품과 N:M)
CREATE TABLE "sales"."promotions" (
    -- 프로모션 ID
    "id" bigserial NOT NULL,
    -- 프로모션 이름
    "name" varchar(100) NOT NULL,
    -- 할인율 (%)
    "discount_rate" numeric(5, 2) NOT NULL,
    -- 최대 할인 금액 (근사값)
    "max_discount" double precision,
    -- 시작일
    "starts_on" date NOT NULL,
    -- 종료일
    "ends_on" date NOT NULL,
    PRIMARY KEY ("id"),
    CHECK (ends_on >= starts_on)
);
COMMENT ON TABLE "sales"."promotions" IS '프로모션 (상품과 N:M)';
COMMENT ON COLUMN "sales"."promotions"."id" IS '프로모션 ID';
COMMENT ON COLUMN "sales"."promotions"."name" IS '프로모션 이름';
COMMENT ON COLUMN "sales"."promotions"."discount_rate" IS '할인율 (%)';
COMMENT ON COLUMN "sales"."promotions"."max_discount" IS '최대 할인 금액 (근사값)';
COMMENT ON COLUMN "sales"."promotions"."starts_on" IS '시작일';
COMMENT ON COLUMN "sales"."promotions"."ends_on" IS '종료일';

-- 택배사
CREATE TABLE "shipping"."carriers" (
    -- 택배사 ID
    "id" bigserial NOT NULL,
    -- 택배사 코드
    "code" varchar(20) NOT NULL UNIQUE,
    -- 택배사 이름
    "name" varchar(50) NOT NULL,
    -- 배송 조회 주소 ({trackingNo} 치환)
    "tracking_url_template" varchar(300),
    -- 사용 여부
    "is_active" boolean NOT NULL DEFAULT true,
    PRIMARY KEY ("id")
);
COMMENT ON TABLE "shipping"."carriers" IS '택배사';
COMMENT ON COLUMN "shipping"."carriers"."id" IS '택배사 ID';
COMMENT ON COLUMN "shipping"."carriers"."code" IS '택배사 코드';
COMMENT ON COLUMN "shipping"."carriers"."name" IS '택배사 이름';
COMMENT ON COLUMN "shipping"."carriers"."tracking_url_template" IS '배송 조회 주소 ({trackingNo} 치환)';
COMMENT ON COLUMN "shipping"."carriers"."is_active" IS '사용 여부';

-- 배송
CREATE TABLE "shipping"."shipments" (
    -- 배송 ID
    "id" bigserial NOT NULL,
    -- 주문
    "order_id" bigint NOT NULL,
    -- 택배사
    "carrier_id" bigint,
    -- 출고 창고
    "warehouse_id" bigint NOT NULL,
    -- 운송장 번호
    "tracking_no" varchar(40),
    -- 배송 상태
    "status" shipment_status NOT NULL DEFAULT 'READY',
    -- 배송지
    "address" postal_address NOT NULL,
    -- 받는 사람
    "recipient" varchar(50) NOT NULL,
    -- 받는 사람 연락처
    "recipient_phone" varchar(20) NOT NULL,
    -- 출고 일시
    "shipped_at" timestamp,
    -- 배송 완료 일시
    "delivered_at" timestamp,
    PRIMARY KEY ("id"),
    CHECK (delivered_at IS NULL OR delivered_at >= shipped_at)
);
COMMENT ON TABLE "shipping"."shipments" IS '배송';
COMMENT ON COLUMN "shipping"."shipments"."id" IS '배송 ID';
COMMENT ON COLUMN "shipping"."shipments"."order_id" IS '주문';
COMMENT ON COLUMN "shipping"."shipments"."carrier_id" IS '택배사';
COMMENT ON COLUMN "shipping"."shipments"."warehouse_id" IS '출고 창고';
COMMENT ON COLUMN "shipping"."shipments"."tracking_no" IS '운송장 번호';
COMMENT ON COLUMN "shipping"."shipments"."status" IS '배송 상태';
COMMENT ON COLUMN "shipping"."shipments"."address" IS '배송지';
COMMENT ON COLUMN "shipping"."shipments"."recipient" IS '받는 사람';
COMMENT ON COLUMN "shipping"."shipments"."recipient_phone" IS '받는 사람 연락처';
COMMENT ON COLUMN "shipping"."shipments"."shipped_at" IS '출고 일시';
COMMENT ON COLUMN "shipping"."shipments"."delivered_at" IS '배송 완료 일시';
-- Indexes
CREATE INDEX "shipments_idx_shipments_order" ON "shipping"."shipments" ("order_id");
CREATE INDEX "shipments_idx_shipments_status" ON "shipping"."shipments" USING HASH ("status");
CREATE UNIQUE INDEX "shipments_uk_shipments_tracking" ON "shipping"."shipments" ("carrier_id", "tracking_no");

-- 배송 상품 (부분 배송)
CREATE TABLE "shipping"."shipment_items" (
    -- 배송 상품 ID
    "id" bigserial NOT NULL,
    -- 배송
    "shipment_id" bigint NOT NULL,
    -- 주문 상품
    "order_item_id" bigint NOT NULL,
    -- 배송 수량
    "quantity" int NOT NULL,
    PRIMARY KEY ("id")
);
COMMENT ON TABLE "shipping"."shipment_items" IS '배송 상품 (부분 배송)';
COMMENT ON COLUMN "shipping"."shipment_items"."id" IS '배송 상품 ID';
COMMENT ON COLUMN "shipping"."shipment_items"."shipment_id" IS '배송';
COMMENT ON COLUMN "shipping"."shipment_items"."order_item_id" IS '주문 상품';
COMMENT ON COLUMN "shipping"."shipment_items"."quantity" IS '배송 수량';

-- 배송 추적 이력
CREATE TABLE "shipping"."shipment_tracking_events" (
    -- 이력 ID
    "id" bigserial NOT NULL,
    -- 배송
    "shipment_id" bigint NOT NULL,
    -- 그 시점의 상태
    "status" shipment_status NOT NULL,
    -- 위치
    "location" varchar(100),
    -- 설명
    "description" varchar(300),
    -- 발생 시각
    "occurred_at" timestamptz NOT NULL,
    PRIMARY KEY ("id")
);
COMMENT ON TABLE "shipping"."shipment_tracking_events" IS '배송 추적 이력';
COMMENT ON COLUMN "shipping"."shipment_tracking_events"."id" IS '이력 ID';
COMMENT ON COLUMN "shipping"."shipment_tracking_events"."shipment_id" IS '배송';
COMMENT ON COLUMN "shipping"."shipment_tracking_events"."status" IS '그 시점의 상태';
COMMENT ON COLUMN "shipping"."shipment_tracking_events"."location" IS '위치';
COMMENT ON COLUMN "shipping"."shipment_tracking_events"."description" IS '설명';
COMMENT ON COLUMN "shipping"."shipment_tracking_events"."occurred_at" IS '발생 시각';
-- Indexes
CREATE INDEX "shipment_tracking_events_idx_tracking_events_shipment" ON "shipping"."shipment_tracking_events" ("shipment_id", "occurred_at");

-- 반품/교환 요청
CREATE TABLE "shipping"."return_requests" (
    -- 요청 ID
    "id" bigserial NOT NULL,
    -- 배송 상품
    "shipment_item_id" bigint NOT NULL,
    -- 요청자
    "requested_by" bigint NOT NULL,
    -- 반품/교환
    "return_type" return_type NOT NULL,
    -- 처리 상태
    "status" return_status NOT NULL DEFAULT 'REQUESTED',
    -- 사유
    "reason" varchar(500) NOT NULL,
    -- 수량
    "quantity" int NOT NULL DEFAULT 1,
    -- 요청 일시
    "requested_at" timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- 완료 일시
    "completed_at" timestamp,
    PRIMARY KEY ("id")
);
COMMENT ON TABLE "shipping"."return_requests" IS '반품/교환 요청';
COMMENT ON COLUMN "shipping"."return_requests"."id" IS '요청 ID';
COMMENT ON COLUMN "shipping"."return_requests"."shipment_item_id" IS '배송 상품';
COMMENT ON COLUMN "shipping"."return_requests"."requested_by" IS '요청자';
COMMENT ON COLUMN "shipping"."return_requests"."return_type" IS '반품/교환';
COMMENT ON COLUMN "shipping"."return_requests"."status" IS '처리 상태';
COMMENT ON COLUMN "shipping"."return_requests"."reason" IS '사유';
COMMENT ON COLUMN "shipping"."return_requests"."quantity" IS '수량';
COMMENT ON COLUMN "shipping"."return_requests"."requested_at" IS '요청 일시';
COMMENT ON COLUMN "shipping"."return_requests"."completed_at" IS '완료 일시';

-- Foreign key constraints
-- Schema: catalog
ALTER TABLE "catalog"."categories" ADD CONSTRAINT "fk_categories_parent_id_categories_id" FOREIGN KEY("parent_id") REFERENCES "catalog"."categories"("id");
ALTER TABLE "catalog"."products" ADD CONSTRAINT "fk_products_category_id_categories_id" FOREIGN KEY("category_id") REFERENCES "catalog"."categories"("id");
ALTER TABLE "catalog"."products" ADD CONSTRAINT "fk_products_brand_id_brands_id" FOREIGN KEY("brand_id") REFERENCES "catalog"."brands"("id");
ALTER TABLE "catalog"."product_options" ADD CONSTRAINT "fk_product_options_product_id_products_id" FOREIGN KEY("product_id") REFERENCES "catalog"."products"("id");
ALTER TABLE "catalog"."product_images" ADD CONSTRAINT "fk_product_images_product_id_products_id" FOREIGN KEY("product_id") REFERENCES "catalog"."products"("id");
ALTER TABLE "catalog"."product_tags" ADD CONSTRAINT "fk_product_tags_product_id_products_id" FOREIGN KEY("product_id") REFERENCES "catalog"."products"("id");
ALTER TABLE "catalog"."product_tags" ADD CONSTRAINT "fk_product_tags_tag_id_tags_id" FOREIGN KEY("tag_id") REFERENCES "catalog"."tags"("id");
ALTER TABLE "catalog"."product_reviews" ADD CONSTRAINT "fk_product_reviews_product_id_products_id" FOREIGN KEY("product_id") REFERENCES "catalog"."products"("id");
ALTER TABLE "catalog"."product_reviews" ADD CONSTRAINT "fk_product_reviews_user_id_users_id" FOREIGN KEY("user_id") REFERENCES "member"."users"("id");

-- Schema: inventory
ALTER TABLE "inventory"."warehouses" ADD CONSTRAINT "fk_warehouses_manager_id_users_id" FOREIGN KEY("manager_id") REFERENCES "member"."users"("id");
ALTER TABLE "inventory"."stocks" ADD CONSTRAINT "fk_stocks_warehouse_id_warehouses_id" FOREIGN KEY("warehouse_id") REFERENCES "inventory"."warehouses"("id");
ALTER TABLE "inventory"."stocks" ADD CONSTRAINT "fk_stocks_product_option_id_product_options_id" FOREIGN KEY("product_option_id") REFERENCES "catalog"."product_options"("id");
ALTER TABLE "inventory"."stock_movements" ADD CONSTRAINT "fk_stock_movements_stock_id_stocks_id" FOREIGN KEY("stock_id") REFERENCES "inventory"."stocks"("id");
ALTER TABLE "inventory"."stock_movements" ADD CONSTRAINT "fk_stock_movements_order_id_orders_id" FOREIGN KEY("order_id") REFERENCES "sales"."orders"("id");
ALTER TABLE "inventory"."stock_movements" ADD CONSTRAINT "fk_stock_movements_created_by_users_id" FOREIGN KEY("created_by") REFERENCES "member"."users"("id");
ALTER TABLE "inventory"."inbound_orders" ADD CONSTRAINT "fk_inbound_orders_supplier_id_suppliers_id" FOREIGN KEY("supplier_id") REFERENCES "inventory"."suppliers"("id");
ALTER TABLE "inventory"."inbound_orders" ADD CONSTRAINT "fk_inbound_orders_warehouse_id_warehouses_id" FOREIGN KEY("warehouse_id") REFERENCES "inventory"."warehouses"("id");
ALTER TABLE "inventory"."inbound_orders" ADD CONSTRAINT "fk_inbound_orders_product_option_id_product_options_id" FOREIGN KEY("product_option_id") REFERENCES "catalog"."product_options"("id");

-- Schema: member
ALTER TABLE "member"."users" ADD CONSTRAINT "fk_users_grade_id_member_grades_id" FOREIGN KEY("grade_id") REFERENCES "member"."member_grades"("id");
ALTER TABLE "member"."users" ADD CONSTRAINT "fk_users_id_user_profiles_user_id" FOREIGN KEY("id") REFERENCES "member"."user_profiles"("user_id");
ALTER TABLE "member"."user_addresses" ADD CONSTRAINT "fk_user_addresses_user_id_users_id" FOREIGN KEY("user_id") REFERENCES "member"."users"("id");

-- Schema: sales
ALTER TABLE "sales"."orders" ADD CONSTRAINT "fk_orders_user_id_users_id" FOREIGN KEY("user_id") REFERENCES "member"."users"("id");
ALTER TABLE "sales"."order_items" ADD CONSTRAINT "fk_order_items_order_id_orders_id" FOREIGN KEY("order_id") REFERENCES "sales"."orders"("id");
ALTER TABLE "sales"."order_items" ADD CONSTRAINT "fk_order_items_product_option_id_product_options_id" FOREIGN KEY("product_option_id") REFERENCES "catalog"."product_options"("id");
ALTER TABLE "sales"."orders" ADD CONSTRAINT "fk_orders_id_payments_order_id" FOREIGN KEY("id") REFERENCES "sales"."payments"("order_id");

-- Schema: shipping
ALTER TABLE "shipping"."shipments" ADD CONSTRAINT "fk_shipments_order_id_orders_id" FOREIGN KEY("order_id") REFERENCES "sales"."orders"("id");
ALTER TABLE "shipping"."shipments" ADD CONSTRAINT "fk_shipments_carrier_id_carriers_id" FOREIGN KEY("carrier_id") REFERENCES "shipping"."carriers"("id");
ALTER TABLE "shipping"."shipments" ADD CONSTRAINT "fk_shipments_warehouse_id_warehouses_id" FOREIGN KEY("warehouse_id") REFERENCES "inventory"."warehouses"("id");
ALTER TABLE "shipping"."shipment_items" ADD CONSTRAINT "fk_shipment_items_shipment_id_shipments_id" FOREIGN KEY("shipment_id") REFERENCES "shipping"."shipments"("id");
ALTER TABLE "shipping"."shipment_items" ADD CONSTRAINT "fk_shipment_items_order_item_id_order_items_id" FOREIGN KEY("order_item_id") REFERENCES "sales"."order_items"("id");
ALTER TABLE "shipping"."shipment_tracking_events" ADD CONSTRAINT "fk_shipment_tracking_events_shipment_id_shipments_id" FOREIGN KEY("shipment_id") REFERENCES "shipping"."shipments"("id");
ALTER TABLE "shipping"."return_requests" ADD CONSTRAINT "fk_return_requests_shipment_item_id_shipment_items_id" FOREIGN KEY("shipment_item_id") REFERENCES "shipping"."shipment_items"("id");
ALTER TABLE "shipping"."return_requests" ADD CONSTRAINT "fk_return_requests_requested_by_users_id" FOREIGN KEY("requested_by") REFERENCES "member"."users"("id");