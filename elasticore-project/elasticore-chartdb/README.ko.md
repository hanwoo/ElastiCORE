# elasticore-chartdb

[English](README.md) · [상세 가이드](GUIDE.ko.md)

[ChartDB](https://github.com/chartdb/chartdb) 다이어그램(“Export > JSON”이 쓰고 “Import > JSON”이 읽는 JSON)과
ElastiCORE blueprint를 양방향으로 변환합니다.

```
ChartDB 다이어그램 JSON  --to-yaml-->  <blueprint>/<domain>/env.yml
                                       <blueprint>/<domain>/model/*.yml
                                       <blueprint>/chartdb.yml          (사이드카)
ChartDB 다이어그램 JSON  <--to-json--  같은 blueprint (사이드카가 없어도 됨)
```

## 사용법

```bash
# 다이어그램 -> blueprint
./gradlew :elasticore-project:elasticore-chartdb:chartdbToYaml \
    -Pjson=shop.chartdb.json -Pblueprint=src/main/resources/blueprint \
    -Pdomain=shop -Ppackage=com.acme.shop [-Pexpose=20] [-Pforce=true]

# blueprint -> 다이어그램
./gradlew :elasticore-project:elasticore-chartdb:chartdbToJson \
    -Pblueprint=src/main/resources/blueprint -Pjson=shop.chartdb.json [-Pdatabase=postgresql] [-Pname=Shop]

# 왕복 변환 후 달라진 것을 보고
./gradlew :elasticore-project:elasticore-chartdb:chartdbVerify -Pjson=shop.chartdb.json [-Pwork=build/chartdb-verify]
```

상대 경로는 저장소 루트 기준입니다. 같은 명령을
`io.elasticore.chartdb.ChartDbConverter to-yaml|to-json|verify --key=value ...`로도 실행할 수 있습니다.

## 무엇이 어디로 가는가

모델은 DSL로, 그림(배치)과 DSL에 표현 수단이 없는 DB 정보는 사이드카 `chartdb.yml`로 갑니다.
사이드카에는 다이어그램과 blueprint의 차이만 들어가므로 모델이 두 번 기술되지 않습니다.

| ChartDB | Blueprint DSL |
|---|---|
| 테이블, 스키마 | `entity @table(name) @schema(name)`, 스키마마다 `model/<schema>.yml` |
| 뷰, materialized view | `entity @immutable` (materialized 여부는 사이드카) |
| 컬럼 타입, 길이, 정밀도 | DSL 타입, `string(40)`, `decimal(12.2)` |
| DSL에 없는 타입(`jsonb`, `inet`, 배열 등) | 가장 가까운 DSL 타입 + `@columnDef('...')` |
| NOT NULL / PK / 자동 증가 / UNIQUE | `!` / `@id` / `@sequence` / `@unique` |
| 기본값 | `@default(...)` |
| 컬럼·테이블 주석 | `-- 주석` |
| 외래 키(N:1, 1:1) | 엔티티 참조, `@OneToOne` |
| 복합 키에 속한 외래 키 | `@id @fk(Entity.field)` |
| N:M | `List<Entity> @ManyToMany` |
| 인덱스 | `@Index(name=..., columnList=..., unique=...)` |
| check 제약 | `@check("...")`, 범위이면 `@min`/`@max`도 함께 |
| enum 커스텀 타입 | `model/enum.yml`의 `enum @db(code) @json(code)` |
| composite 커스텀 타입 | `entity @embeddable`, 사용처는 `@Embedded` |

사이드카에 들어가는 것: 테이블 위치·크기·색·순서·소속 area, area, note, 뷰 dependency,
인덱스 타입(btree, gin 등)과 주석, collation, 관계 이름, 그리고 DSL 표기로는 그대로 담을 수 없는 값
(예: 고쳐 써야 했던 주석의 원문).

사이드카가 없는 blueprint도 변환됩니다. 테이블은 열 단위로 자동 배치되고, 도메인(바운디드 컨텍스트)이나
스키마마다 area가 만들어집니다.

## 검증한 것

- `samples/shop/shop.chartdb.json`(온라인 쇼핑몰: 회원, 상품, 재고, 주문, 배송, 현황. 테이블 28, 관계 34,
  커스텀 타입 12, area 6, note 2, 뷰 dependency 4)이 blueprint로 갔다가 바이트 단위로 같게 돌아옵니다.
  이 파일은 `samples/shop/build_shop_erd.py`가 생성합니다.
- ChartDB에 들어 있는 템플릿 다이어그램 50개가 차이 없이 왕복됩니다
  (`./gradlew :elasticore-project:elasticore-chartdb:test -Dchartdb.extra=<json 폴더>`).
- `elasticore-sample-petstore`, `elasticore-template-ddd`의 blueprint로 만든 다이어그램이 ChartDB 자체
  import 스키마를 통과합니다.
- 쇼핑몰 다이어그램에서 만든 blueprint를 `elcore3`이 받아들이고, 생성된 코드가 컴파일됩니다
  (Spring Boot 4에서 hexagonal·ddd, Spring Boot 3에서 layered).

## 한계

- id는 보존하지 않습니다. ChartDB가 import할 때마다 새로 부여하므로 이름으로 비교합니다.
- 뷰의 SQL은 ChartDB 다이어그램 형식에 없어서 변환 대상이 아닙니다.
- PK가 없는 뷰는 DSL에서 컬럼 하나에 `@id`가 붙습니다(JPA 요구 사항). 다이어그램에서는 원래대로 유지됩니다.
