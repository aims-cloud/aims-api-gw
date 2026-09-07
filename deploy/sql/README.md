# `deploy/sql` — 스키마 스크립트

배포 VM 은 환경파일이 `SPRING_JPA_HIBERNATE_DDL_AUTO=update` 로 리포 기본값을 덮어써서, 대부분의 스키마 변경을
Hibernate 가 기동 때 알아서 반영한다. 그래도 스크립트를 남기는 이유는 두 가지다.

1. **리포 기본값은 `validate`** 다(`application.yml`). 그 환경변수가 없는 새 DB 는 스크립트로만 스키마를 갖춘다.
2. **`ddl-auto=update` 가 절대 손대지 않는 것**이 있다 — 기존 CHECK 제약의 수정, 부분 유니크 인덱스.

## 새 DB 를 처음 세울 때 — 실행 순서가 중요하다

파일명은 **작성 날짜**이지 실행 순서가 아니다. 스케줄러 스크립트는 아래 순서로 돌려야 한다.
날짜순으로 몰아서 돌리면 `2026-08-31_*` 의 `ALTER TABLE workloads ...` 가 그 표를 만들기 전에 실행되어 실패한다.

```
psql -U aims -d aims -v ON_ERROR_STOP=1 -f 2026-09-04_scheduler_base_tables.sql   # ← 반드시 먼저
psql -U aims -d aims -v ON_ERROR_STOP=1 -f 2026-08-31_schedule_api.sql
psql -U aims -d aims -v ON_ERROR_STOP=1 -f 2026-08-31_scheduler_extension.sql
psql -U aims -d aims -v ON_ERROR_STOP=1 -f 2026-09-02_mdc_links.sql
psql -U aims -d aims -v ON_ERROR_STOP=1 -f 2026-09-03_placement_dispatches.sql
psql -U aims -d aims -v ON_ERROR_STOP=1 -f 2026-09-03_schedule_placement_order.sql
psql -U aims -d aims -v ON_ERROR_STOP=1 -f 2026-09-04_node_power_specs.sql
psql -U aims -d aims -v ON_ERROR_STOP=1 -f 2026-09-04_mdc_power_budgets.sql
```

`-v ON_ERROR_STOP=1` 을 붙인다. psql 기본값은 문장이 실패해도 계속 진행이라, 제약 하나가 안 걸린 것을 모른 채
끝까지 돌아 버린다.

스케줄러 밖 스크립트(`asset_extension`·`rack_extension`·`vm_groups`·`role_assets`·`role_namespaces`·
`monitoring_target_*`)는 다른 담당 영역이라 여기서 순서를 정하지 않는다.

## 이미 돌아가는 DB 에 새 스크립트를 적용할 때

새로 추가된 스크립트 하나만 돌리면 된다. 모든 스크립트는 `IF NOT EXISTS` / `DROP ... IF EXISTS` 후 재생성이라
여러 번 돌려도 결과가 같다.

주의: `CREATE TABLE IF NOT EXISTS` 는 표가 하나라도 있으면 **표 전체가 no-op** 이다 — 컬럼을 하나 더하려면
`ALTER TABLE ... ADD COLUMN IF NOT EXISTS` 를 따로 써야 한다.

## 스크립트가 빠지지 않게 하는 장치

`SchedulerDdlCoverageTest` 가 스케줄러 엔티티의 `@Table` 이름과 이 디렉터리의 `CREATE TABLE` 대상을 대조한다.
표를 새로 만들면서 스크립트를 잊으면 그 테스트가 빨간불이 된다. 다만 대조는 **이름 수준**이라, 컬럼 타입·길이·
NOT NULL 이 맞는지는 배포 뒤 `information_schema` 로 확인해야 한다(테스트가 H2 라 실제 PostgreSQL 스키마를 못 본다).
