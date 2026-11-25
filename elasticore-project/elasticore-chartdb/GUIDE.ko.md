# ChartDB JSON ↔ ElastiCORE YAML 변환 가이드

ChartDB 다이어그램 JSON과 ElastiCORE blueprint(YAML)를 서로 변환하고, JSON을 ChartDB에서 ERD로 여는
방법을 순서대로 정리한 문서입니다. 요약과 매핑 표만 필요하면 [README.ko.md](README.ko.md)를 보세요.

- [1. 전체 흐름](#1-전체-흐름)
- [2. 준비](#2-준비)
- [3. JSON을 ERD로 보기 (ChartDB)](#3-json을-erd로-보기-chartdb)
- [4. JSON → YAML](#4-json--yaml)
- [5. YAML → JSON](#5-yaml--json)
- [6. 왕복 검증](#6-왕복-검증)
- [7. 일상 작업 순서](#7-일상-작업-순서)
- [8. 변환 규칙](#8-변환-규칙)
- [9. 사이드카 chartdb.yml](#9-사이드카-chartdbyml)
- [10. 콘솔 메시지 읽는 법](#10-콘솔-메시지-읽는-법)
- [11. 문제 해결](#11-문제-해결)

## 1. 전체 흐름

```
        ChartDB (브라우저)
   Export Diagram │ ▲ Import / Restore Diagram
                  ▼ │
          shop.chartdb.json
     chartdbToYaml │ ▲ chartdbToJson
                   ▼ │
   blueprint/
   ├─ chartdb.yml              ← 사이드카: 배치, 색, area, note 등 그림 정보
   └─ shop/
      ├─ env.yml               ← 도메인 설정
      └─ model/*.yml           ← 엔티티, enum (스키마마다 파일 하나)
                   │
                   ▼ ./gradlew elcore3
        Spring Boot 코드 (엔티티, 서비스, REST …)
```

- **모델**(테이블, 컬럼, 키, 관계, 인덱스, 제약)은 `model/*.yml`의 DSL로 갑니다.
- **그림**(위치, 색, area, note)과 DSL에 표현 수단이 없는 DB 정보는 `chartdb.yml`로 갑니다.
- 두 파일을 합치면 원래 JSON이 그대로 나옵니다.

## 2. 준비

| 항목 | 내용 |
|---|---|
| JDK | 17 이상 (저장소 빌드 요구 사항. 이 모듈 자체는 Java 8 문법) |
| Gradle | 저장소의 wrapper(8.8) |
| 실행 위치 | 저장소 루트 `ElastiCORE/`. 상대 경로는 모두 루트 기준 |

아래 예시는 `./gradlew`로 적었습니다. Windows `cmd`에서는 `gradlew.bat`을 씁니다.

> 로컬 작업본에서 `gradle/wrapper/gradle-wrapper.properties`가 9.7.1로 바뀌어 있다면
> `git checkout gradle/wrapper/gradle-wrapper.properties`로 8.8로 되돌린 뒤 실행하세요.
> 이 모듈은 Gradle 8.8에서만 확인했습니다.

태스크 이름이 길어서 아래에서는 `:elasticore-project:elasticore-chartdb:`를 `:…chartdb:`로 줄여 쓰지 않고
전체를 적습니다. 그대로 복사해서 쓰면 됩니다.

## 3. JSON을 ERD로 보기 (ChartDB)

### 3.1 ChartDB 열기

세 가지 중 하나를 고릅니다. 어느 쪽이든 다이어그램은 브라우저의 IndexedDB에 저장되고 서버로 올라가지 않습니다.

**A. 클라우드 버전** — 설치 없이 <https://app.chartdb.io> 접속. 계정이 필요 없습니다.

**B. Docker**

```bash
docker run -p 8080:80 ghcr.io/chartdb/chartdb:latest
# 브라우저에서 http://localhost:8080
```

**C. 소스에서 실행**

```bash
git clone https://github.com/chartdb/chartdb
cd chartdb
npm install
npm run dev        # 콘솔에 나오는 주소(기본 http://localhost:5173)로 접속
```

### 3.2 JSON 불러오기

1. ChartDB를 열면 처음에 DB 종류를 고르는 창이 뜹니다. 빈 다이어그램을 하나 만들거나 창을 닫습니다.
2. 상단 메뉴에서 **Backup → Restore Diagram**을 고릅니다.
   (버전에 따라 **File → Import → .json**으로 표시됩니다. 대화상자 제목은 “Import Diagram”입니다.)
3. JSON 파일을 끌어다 놓거나 선택하고 **Import**를 누릅니다.
4. 새 다이어그램으로 열립니다. 현재 열려 있던 다이어그램은 그대로 남습니다.

바로 해 볼 수 있는 파일: `elasticore-project/elasticore-chartdb/samples/shop/shop.chartdb.json`
(온라인 쇼핑몰 ERD. 테이블 28, 관계 34, area 6, note 2)

### 3.3 화면에서 확인할 것

| 보이는 것 | JSON의 위치 |
|---|---|
| 색이 칠해진 큰 사각형(영역) | `areas` |
| 테이블 카드와 헤더 색 | `tables[].x/y/color/width` |
| 테이블 사이의 선과 1 / N 표시 | `relationships[].sourceCardinality/targetCardinality` |
| 뷰에서 원본 테이블로 가는 점선 | `dependencies` |
| 메모지 | `notes` |
| 왼쪽 패널의 Custom Types | `customTypes` (enum, composite) |
| 테이블을 펼쳤을 때의 Indexes, Check | `tables[].indexes`, `tables[].checkConstraints` |

스키마가 여러 개인 다이어그램은 왼쪽 위의 스키마 필터에서 보일 스키마를 고를 수 있습니다.
전부 보려면 모든 스키마를 선택합니다.

### 3.4 ChartDB에서 JSON 내보내기

편집한 다이어그램을 다시 변환하려면 **Backup → Export Diagram**(또는 **File → Export as → JSON**)에서
JSON 형식을 골라 저장합니다. 이 파일이 4장의 입력입니다.

> ChartDB는 불러오고 내보낼 때마다 `id`를 새로 붙입니다. 그래서 변환기는 `id`가 아니라 이름
> (`스키마.테이블.컬럼`)으로 대상을 찾고 비교합니다. 내보낸 파일의 id가 바뀌어 있어도 정상입니다.

### 3.5 SQL로도 확인하기

ChartDB의 **File → Export SQL**에서 DB 종류를 고르면 같은 다이어그램의 DDL을 볼 수 있습니다.
쇼핑몰 샘플의 결과는 `samples/shop/shop.chartdb.sql`에 넣어 두었습니다.

## 4. JSON → YAML

### 4.1 명령

```bash
./gradlew :elasticore-project:elasticore-chartdb:chartdbToYaml \
    -Pjson=elasticore-project/elasticore-chartdb/samples/shop/shop.chartdb.json \
    -Pblueprint=build/shop-blueprint \
    -Pdomain=shop \
    -Ppackage=com.acme.shop
```

| 옵션 | 필수 | 뜻 | 기본값 |
|---|---|---|---|
| `-Pjson` | ○ | ChartDB에서 내보낸 JSON 파일 | |
| `-Pblueprint` | ○ | blueprint 루트 폴더. 보통 `src/main/resources/blueprint` | |
| `-Pdomain` | | 도메인 이름. `<blueprint>/<domain>/` 폴더가 됩니다 | `erd` |
| `-Ppackage` | | 생성될 코드의 기본 패키지 | `com.example.<domain>` |
| `-Pexpose` | | 숫자를 주면 모든 테이블에 `@expose(n)`을 붙여 REST API를 엽니다(n은 페이지 크기) | 붙이지 않음 |
| `-Pforce` | | `true`이면 이미 있는 도메인을 덮어씁니다 | `false` |

`<blueprint>/<domain>/env.yml`이 이미 있으면 멈춥니다. 손으로 고친 blueprint를 실수로 덮어쓰지 않기
위한 것이고, 덮어쓰려면 `-Pforce=true`를 줍니다.

### 4.2 만들어지는 파일

```
build/shop-blueprint/
├─ chartdb.yml                 사이드카 (9장)
└─ shop/
   ├─ env.yml                  도메인 이름, 패키지, API 경로
   └─ model/
      ├─ member.yml            스키마 member의 테이블
      ├─ catalog.yml           스키마 catalog의 테이블
      ├─ inventory.yml
      ├─ sales.yml
      ├─ shipping.yml
      ├─ report.yml            뷰, materialized view
      ├─ enum.yml              enum 커스텀 타입
      └─ value.yml             composite 커스텀 타입 (@embeddable)
```

스키마가 없는 다이어그램(MySQL 등)은 엔티티가 파일 하나에 모입니다.

### 4.3 결과 예

ChartDB의 `catalog.products` 테이블은 `model/catalog.yml`에서 이렇게 됩니다(일부).

```yaml
  Product:
    meta: entity @table(products) @schema(catalog)
          @Index(name="idx_products_category_status", columnList="category_id,status")
          @check("sale_price >= 0 AND sale_price <= list_price") -- 상품
    fields:
      id:           long @id @sequence -- 상품 ID
      category:     Category! -- 분류
      brand:        Brand -- 브랜드
      code:         string(40)! @unique -- 상품 코드
      name:         string(200)! -- 상품 이름
      description:  text -- 상세 설명
      listPrice:    decimal(12.2)! -- 정가
      keywords:     string[] @columnDef('varchar(50)[]') -- 검색 키워드
      attributes:   string @columnDef('jsonb') -- 상품 속성 (색상, 소재 ...)
      createdAt:    datetime! @default(now) -- 등록 일시
```

- `category_id` 외래 키 컬럼은 엔티티 참조 `category: Category!`가 되었습니다.
- `!`는 NOT NULL, `-- …`는 컬럼 주석입니다.
- DSL에 없는 타입 `jsonb`는 `string`에 `@columnDef`로 원래 타입을 남겼습니다.

### 4.4 코드 생성까지

만든 blueprint를 Spring Boot 프로젝트의 `src/main/resources/blueprint`에 두고 생성합니다.

```bash
./gradlew elcore3        # 프로젝트의 build.gradle에 io.elasticore 플러그인이 있어야 합니다
./gradlew compileJava
```

쇼핑몰 샘플로 확인한 조합은 다음과 같습니다.

| 아키텍처 | Spring Boot | 결과 |
|---|---|---|
| hexagonal | 4 | 생성·컴파일 성공 |
| ddd | 4 | 생성·컴파일 성공 |
| layered | 3 | 컴파일 성공 |
| layered | 4 | 컴파일 실패 — 생성된 `XxxCoreService`의 `delete(Specification)` 호출 27건. 변환기가 아니라 생성기의 layered 템플릿 쪽 문제 |

## 5. YAML → JSON

### 5.1 명령

```bash
./gradlew :elasticore-project:elasticore-chartdb:chartdbToJson \
    -Pblueprint=build/shop-blueprint \
    -Pjson=build/shop.chartdb.json
```

| 옵션 | 필수 | 뜻 | 기본값 |
|---|---|---|---|
| `-Pblueprint` | ○ | blueprint 루트 폴더 (도메인 폴더들의 부모) | |
| `-Pjson` | ○ | 쓸 JSON 파일 | |
| `-Pdatabase` | | ChartDB의 DB 종류: `postgresql`, `mysql`, `mariadb`, `sql_server`, `sqlite`, `oracle` 등 | 사이드카의 값, 없으면 `postgresql` |
| `-Pname` | | 다이어그램 이름 | 사이드카의 값 |

만든 JSON은 3.2절의 방법으로 ChartDB에서 엽니다.

### 5.2 사이드카가 있을 때

`chartdbToYaml`로 만든 blueprint처럼 `chartdb.yml`이 있으면 테이블 위치, 색, area, note가 그대로
복원됩니다. DSL을 고친 부분만 다이어그램에 반영됩니다.

- 엔티티나 필드를 **추가**하면: 새 컬럼이 테이블에 나타나고, 새 테이블은 기존 그림 아래쪽에 놓입니다.
- **삭제**하면: 다이어그램에서도 빠지고, 사이드카에 남은 그 항목은 무시됩니다.
- 테이블 **이름을 바꾸면**: 새 테이블로 취급되어 위치가 초기화됩니다. 위치를 유지하려면 `chartdb.yml`의
  해당 키(`스키마.테이블`)도 같이 바꿉니다.

### 5.3 사이드카가 없을 때 (손으로 쓴 blueprint)

기존 ElastiCORE 프로젝트의 blueprint도 그대로 변환됩니다.

```bash
# 예: petstore 샘플
./gradlew :elasticore-project:elasticore-chartdb:chartdbToJson \
    -Pblueprint=elasticore-project/elasticore-sample-petstore/src/main/resources/blueprint \
    -Pjson=build/petstore.chartdb.json
```

- 테이블은 열 단위로 자동 배치됩니다.
- 도메인(바운디드 컨텍스트)이나 스키마마다 area가 하나씩 만들어집니다.
- 테이블·컬럼 이름은 생성기와 같은 규칙으로 정해집니다(`env.yml`의 테이블 접두어, `referenceColumn: short` 포함).
- enum과 `@embeddable`은 PostgreSQL에서는 커스텀 타입이 됩니다. 커스텀 타입이 없는 DB(`-Pdatabase=mysql`)에서는
  enum은 저장되는 코드의 `varchar`, 임베디드 값은 그 컬럼들로 풀려서 나옵니다.

확인한 결과: petstore는 테이블 7·관계 5·커스텀 타입 5, template-ddd는 테이블 11·관계 7·커스텀 타입 6이고
area는 `billing`, `catalog`, `shop` 세 개입니다.

## 6. 왕복 검증

JSON → YAML → JSON을 한 번에 돌려서 달라진 것이 있는지 봅니다.

```bash
./gradlew :elasticore-project:elasticore-chartdb:chartdbVerify \
    -Pjson=elasticore-project/elasticore-chartdb/samples/shop/shop.chartdb.json \
    -Pwork=build/chartdb-verify
```

| 옵션 | 필수 | 뜻 | 기본값 |
|---|---|---|---|
| `-Pjson` | ○ | 검사할 JSON | |
| `-Pwork` | | 중간 결과를 둘 폴더 | `build/chartdb-verify` |

성공하면 `round trip: the diagram came back as it went in`이 나오고, 달라진 것이 있으면 한 줄에 하나씩
`LOST  경로: 원래 값 -> 돌아온 값`으로 나옵니다. 작업 폴더에 blueprint와 되돌린 JSON이 남으므로 직접
열어 볼 수 있습니다.

직접 만든 다이어그램을 처음 변환할 때는 `chartdbToYaml`보다 먼저 이 명령을 돌려 보는 것을 권합니다.

폴더에 든 JSON 전체를 검사하려면 테스트를 씁니다.

```bash
./gradlew :elasticore-project:elasticore-chartdb:test -Dchartdb.extra=<JSON이 든 폴더>
```

## 7. 일상 작업 순서

**ERD에서 시작할 때**

1. ChartDB에서 ERD를 그리고 JSON으로 내보냅니다.
2. `chartdbVerify`로 손실이 없는지 확인합니다.
3. `chartdbToYaml`로 프로젝트의 `src/main/resources/blueprint`에 씁니다.
4. `elcore3`으로 코드를 생성합니다.

**blueprint를 고친 뒤 ERD를 갱신할 때**

1. `model/*.yml`을 고칩니다.
2. `chartdbToJson`으로 JSON을 만듭니다.
3. ChartDB에서 불러와 확인하고, 새 테이블의 위치를 정리합니다.
4. 정리한 그림을 남기려면 다시 내보내서 `chartdbToYaml -Pforce=true`로 사이드카를 갱신합니다.

**주의** — 4번의 `-Pforce=true`는 `env.yml`과 `model/*.yml`도 다시 씁니다. 변환기가 쓰지 않는 DSL
(`@access`, `states`, `@search`, 서비스 정의 등)을 손으로 넣어 두었다면 덮어쓰기 전에 커밋해 두고
diff로 되살려야 합니다. 모델은 YAML에서, 그림은 ChartDB에서 고치는 식으로 역할을 나누는 것이 안전합니다.

## 8. 변환 규칙

### 8.1 이름

| ChartDB | DSL | 비고 |
|---|---|---|
| 테이블 `order_items` | 엔티티 `OrderItem` | 복수형은 단수로, snake_case는 PascalCase로 |
| 컬럼 `list_price` | 필드 `listPrice` | camelCase |
| 외래 키 컬럼 `category_id` | 필드 `category` | 참조 필드는 `_id`를 뗍니다 |

DSL 이름에서 생성기가 유도하는 DB 이름이 원래 이름과 다르면 `@table(...)`, `@Column(...)`/`@col(...)`으로
원래 이름을 적어 둡니다. Java 예약어나 생성기가 쓰는 이름과 겹치면 필드 이름을 바꾸고 원래 컬럼 이름을
같은 방식으로 남깁니다.

### 8.2 구조

| ChartDB | DSL |
|---|---|
| 테이블, 스키마 | `entity @table(name) @schema(name)` |
| 뷰 | `entity @immutable` |
| materialized view | `entity @immutable` + 사이드카 `isMaterializedView: true` |
| NOT NULL | 타입 뒤 `!` |
| 기본 키 | `@id` |
| 자동 증가 | `@sequence` |
| UNIQUE 컬럼 | `@unique` |
| 기본값 | `@default(...)` (enum 컬럼은 `@default(EnumName.CONSTANT)`) |
| 테이블·컬럼 주석 | `-- 주석` |
| 인덱스 | `@Index(name="…", columnList="a,b", unique="true")` |
| 테이블 check 제약 | `@check("…")` |
| 컬럼 check | `@check("…")`, 범위이면 `@min`/`@max`도 함께 |
| enum 커스텀 타입 | `model/enum.yml`의 `enum @db(code) @json(code)` |
| composite 커스텀 타입 | `entity @embeddable`, 쓰는 쪽은 `@Embedded` |
| 배열 컬럼 | `타입[]` + `@columnDef` |

### 8.3 관계

| ChartDB 관계 | DSL |
|---|---|
| N:1 (외래 키) | `category: Category!` — 대상 엔티티 타입의 필드 |
| 자기 참조 | `parent: Category` |
| 1:1 | `order: Order! @OneToOne` |
| 복합 기본 키에 속한 외래 키 | `productId: long @id @fk(Product.id)` — 컬럼을 그대로 두고 참조만 표시 |
| N:M | `products: List<Product> @ManyToMany` |
| 뷰 dependency | DSL에 없음 → 사이드카 |

엔티티 참조로 옮길 수 없는 관계(기본 키가 아닌 컬럼을 가리키는 것, 같은 컬럼 쌍의 중복 관계 등)는
사이드카에 그대로 보관되어 JSON으로 되돌릴 때 복원됩니다.

### 8.4 타입

DSL 타입마다 표준 DB 타입이 하나 있습니다. ChartDB의 타입이 표준과 같으면 DSL 타입만 쓰고,
다르면 가장 가까운 DSL 타입에 `@columnDef('원래 타입')`을 붙입니다.

| ChartDB (PostgreSQL 기준) | DSL |
|---|---|
| `varchar(n)` | `string(n)` |
| `text` | `text` |
| `bigint` / `integer` | `long` / `int` |
| `smallint` | `int @columnDef('smallint')` |
| `numeric(p,s)` | `decimal(p.s)` |
| `real` | `float` |
| `boolean` | `boolean` |
| `date` / `timestamp` | `date` / `datetime` |
| `char(2)` | `string @columnDef('char(2)')` |
| `jsonb`, `inet`, `tsvector` 등 | `string @columnDef('…')` |

### 8.5 DSL이 그대로 못 담는 주석

- 주석에 `=`나 짝이 맞지 않는 괄호가 있으면 따옴표로 감싸서 씁니다: `-- "깊이 (1 = 대분류)"`
- `@`는 `(at)`로 바꿔 씁니다.
- 이렇게 고쳐 쓴 주석이나 한 줄로 쓸 수 없는 주석은 원문을 사이드카에 보관하므로, JSON으로 되돌리면
  원래 주석이 나옵니다.

## 9. 사이드카 chartdb.yml

blueprint 루트에 하나 생깁니다. 다이어그램이 말하는 것 중 **blueprint가 말하지 않는 것만** 들어 있습니다.

```yaml
chartdb:
  version: 1
  stamp: 1793836800000
  diagram:
    name: 온라인 쇼핑몰 (Online Shop)
    databaseType: postgresql
    databaseEdition: supabase
  tables:
    member.users:                  # 키는 스키마.테이블
      x: 437
      "y": 70
      color: "#b067e9"
      width: 337
      parentArea: 0                # areas 목록에서의 순번
      fields:
        name:
          collation: ko-KR-x-icu   # DSL에 없는 컬럼 속성
      indexes:
        uk_users_email:
          type: btree              # 인덱스 종류
          comments: 이메일은 한 사람에 하나
  relationships:
    member.users.grade_id>member.member_grades.id:   # 키는 원본컬럼>대상컬럼
      name: fk_users_grade
  customTypes: { … }
  areas: [ … ]
  notes: [ … ]
  dependencies: [ … ]
```

| 항목 | 들어가는 것 |
|---|---|
| `diagram` | 다이어그램 이름, DB 종류·에디션, 생성·수정 시각 |
| `tables.<키>` | 위치(`x`, `y`), `width`, `color`, `order`, `expanded`, 소속 area, materialized 여부 |
| `tables.<키>.fields` | collation, DSL 표기로 정확히 못 담은 타입·기본값·주석 원문 |
| `tables.<키>.indexes` | 인덱스 종류(btree, hash, gin …), 인덱스 주석 |
| `areas`, `notes` | 영역과 메모 전체 |
| `dependencies` | 뷰가 참조하는 테이블 |
| `relationships` | 관계 이름, DSL로 옮기지 못한 관계 |
| `customTypes` | 커스텀 타입의 스키마와 순서 |

`$`로 시작하는 키는 변환기가 쓰는 표시입니다.

| 키 | 뜻 |
|---|---|
| `$new: true` | blueprint에는 없고 다이어그램에만 있는 항목. 통째로 보관한 것 |
| `$unset: [...]` | blueprint에서는 값이 나오지만 다이어그램에는 없는 속성 |
| `$omit: [...]` | blueprint에는 있지만 다이어그램에는 없는 항목 |
| `$order: [...]` | 다이어그램의 순서가 blueprint의 순서와 다를 때의 원래 순서 |

다루는 법:

- **버전 관리에 넣습니다.** 없으면 변환은 되지만 그림이 자동 배치로 바뀝니다.
- 손으로 고쳐도 됩니다(예: 테이블 이름을 바꿨을 때 키 변경). 다만 배치는 ChartDB에서 고치고 다시
  내보내는 편이 쉽습니다.
- 코드 생성기(`elcore3`)는 이 파일을 읽지 않습니다. 생성되는 코드에는 영향이 없습니다.

## 10. 콘솔 메시지 읽는 법

모든 줄은 `[chartdb]`로 시작합니다.

| 접두 | 뜻 | 조치 |
|---|---|---|
| `N table(s), …` | 변환한 개수 | |
| `wrote <파일>` | 쓴 파일 | |
| `note` | 변환기가 내린 결정. 예: `jsonb is not a type the DSL has; kept as a string with @columnDef` | 확인만 |
| `in chartdb.yml (…)` | DSL이 아니라 사이드카로 간 항목의 종류와 개수 | 확인만 |
| `WARN` | 입력에 문제가 있어 그 부분을 빼고 변환함. 예: 없는 테이블을 가리키는 관계 | 원본 다이어그램 수정 |
| `DSL` | 만든 blueprint를 ElastiCORE 로더가 읽으며 낸 메시지 | 비어 있어야 정상. 나오면 변환기 문제 |
| `LOST` | 왕복 후 달라진 값 | 비어 있어야 정상. 나오면 변환기 문제 |

`note` 중 하나는 기억해 둘 만합니다. 기본 키가 없는 뷰는 JPA 엔티티가 될 수 없어서 DSL에서 컬럼 하나에
`@id`를 붙입니다. 다이어그램으로 되돌릴 때는 원래대로(기본 키 없음) 돌아갑니다.

## 11. 문제 해결

| 증상 | 원인과 해결 |
|---|---|
| `… already holds a domain. Pass --force=true …` | 대상 도메인이 이미 있습니다. 덮어쓸 것이면 `-Pforce=true`, 아니면 `-Pdomain`이나 `-Pblueprint`를 바꿉니다 |
| 콘솔의 한글이 깨져 보임 | Windows 콘솔 인코딩 문제입니다. 파일 내용은 UTF-8로 정상입니다. `chcp 65001` 후 실행하면 줄어듭니다 |
| ChartDB에서 불러왔는데 테이블 일부가 안 보임 | 스키마 필터에서 해당 스키마가 꺼져 있습니다. 모든 스키마를 선택합니다 |
| ChartDB가 “Error importing diagram”을 냄 | ChartDB JSON이 아니거나 손으로 고치다 형식이 깨진 파일입니다. `chartdbToJson`으로 다시 만듭니다 |
| 되돌린 JSON의 `id`가 원본과 다름 | 정상입니다. 변환기는 ChartDB가 내보낼 때와 같은 순서로 id를 다시 매깁니다 |
| 사이드카 없이 만든 그림의 배치가 마음에 안 듦 | ChartDB에서 정리한 뒤 내보내서 `chartdbToYaml -Pforce=true`로 사이드카를 만듭니다(7장의 주의 참고) |
| 뷰의 SELECT 문이 안 넘어옴 | ChartDB 다이어그램 JSON에 뷰의 SQL이 없습니다. 변환 대상이 아닙니다 |
| `Unsupported class file major version` 등 Gradle 오류 | wrapper 버전을 확인합니다(2장) |

## 부록: Gradle 없이 실행

같은 기능을 Java 메인 클래스로 직접 실행할 수 있습니다. 클래스패스에는 이 모듈과 `elasticore-base`,
Jackson(databind, dataformat-yaml)이 필요합니다.

```
io.elasticore.chartdb.ChartDbConverter to-yaml --json=… --blueprint=… [--domain=…] [--package=…] [--expose=20] [--force=true]
io.elasticore.chartdb.ChartDbConverter to-json --blueprint=… --json=… [--database=postgresql] [--name=…]
io.elasticore.chartdb.ChartDbConverter verify  --json=… [--work=build/chartdb-verify]
```
