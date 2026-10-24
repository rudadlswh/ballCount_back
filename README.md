<h1 align="center">KBO Score Backend</h1>

<p align="center">
  KBO 경기 데이터를 신뢰 가능한 상태로 정규화하고 실시간으로 전달하는 백엔드
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-17-ED8B00?logo=openjdk&logoColor=white" alt="Java 17">
  <img src="https://img.shields.io/badge/Spring_Boot-3.4.0-6DB33F?logo=springboot&logoColor=white" alt="Spring Boot 3.4.0">
  <img src="https://img.shields.io/badge/PostgreSQL-Flyway-4169E1?logo=postgresql&logoColor=white" alt="PostgreSQL and Flyway">
  <img src="https://img.shields.io/badge/Tests-604-34C759" alt="604 tests">
</p>

## 소개

KBO Score Backend는 공식 KBO 데이터를 수집·파싱·정규화해 iOS 앱에 REST와 SSE로 제공하고, 경기 변화를 APNs·FCM·Live Activity로 전달하는 Spring Boot 서비스입니다.

외부 원천의 지연과 형식 변화, 초 단위 중복 응답, 부분 실패를 정상적인 운영 조건으로 다룹니다. 동일한 입력은 다시 저장하거나 알리지 않고, 트랜잭션이 완료된 상태만 외부로 전달하며, 실패한 작업도 다음 실행과 진단 기록을 잃지 않도록 설계했습니다.

## 핵심 기능

| 영역 | 구현 내용 |
| --- | --- |
| 데이터 수집 | 일정, 경기 상세, 문자 중계, 라인스코어와 박스스코어 수집 |
| 도메인 정규화 | 외부 상태·식별자·취소 사유를 안정적인 앱용 모델로 변환 |
| 적응형 동기화 | 경기 상태에 따라 30분 → 5분/1분 → 1초 → 30초로 수집 주기 전환 |
| 멱등 처리 | SHA-256 raw hash와 고유 event key로 중복 저장·알림 차단 |
| 앱 API | 일정, 스코어보드, 상세, 라인업, 순위와 직관 기록 제공 |
| 실시간 전달 | SSE, APNs, FCM과 Live Activity 업데이트 |
| 운영 도구 | Thymeleaf + HTMX 관리자 콘솔, 작업·실패·로그 조회 |
| 배포 안전성 | 스키마·runtime role·APNs 설정 검증, Docker와 GitHub Actions |

## 기술 스택

- **Runtime:** Java 17, Spring Boot 3.4.0, Gradle 9.3.1
- **Web:** Spring MVC, Thymeleaf, HTMX, springdoc-openapi
- **Data:** PostgreSQL, Spring Data JPA/JDBC, Flyway
- **Collection:** Java HTTP Client, jsoup
- **Realtime:** Server-Sent Events, APNs, FCM, Live Activity
- **Operations:** Docker, Docker Compose, GHCR, GitHub Actions
- **Test:** JUnit 5, Spring Boot Test

현재 코드베이스에는 186개의 main Java 파일, 37개의 Flyway 마이그레이션과 67개 테스트 클래스의 **604개 @Test 선언**이 있습니다.

## 아키텍처

~~~mermaid
flowchart LR
    KBO[Official KBO] --> Client[Crawler Clients]
    Client --> Parser[Parsers / Normalizers]
    Parser --> Sync[Sync Services]
    Sync --> DB[(PostgreSQL)]
    DB --> REST[REST API]
    DB --> SSE[SSE Stream]
    Sync --> Event[Notification Events]
    Event --> APNS[APNs / Live Activity]
    Event --> FCM[FCM]
    DB --> Admin[Admin Console]
~~~

### 신뢰성 설계

1. **상태 기반 스케줄링**

   고정 주기 대신 경기 상태와 시작 시각을 기준으로 다음 실행 시점을 계산합니다.

2. **동시 실행 제어**

   AtomicBoolean로 프로세스 내부 중복 실행을 막고, writer 환경에서는 PostgreSQL advisory lock으로 날짜별 경합을 제어할 수 있습니다.

3. **멱등 저장과 전달**

   정규화한 응답의 SHA-256 hash가 이전 값과 같으면 snapshot, line score, 알림과 Live Activity 갱신을 생략합니다. 알림 event key에는 DB unique constraint를 적용합니다.

4. **커밋 이후 발행**

   SSE와 Live Activity는 트랜잭션이 완료된 뒤 발행해 사용자가 롤백될 상태를 먼저 보지 않도록 합니다.

5. **실패 후 재예약**

   실행 중 예외가 발생해도 다음 tick을 예약합니다. 실패 시 기본 재시도 간격은 10초이며 작업과 실패 기록은 별도 트랜잭션으로 남깁니다.

### FCM 전달 실패 처리

APNs와 FCM 전송, FCM OAuth 토큰 발급은 공통 HTTP 설정을 사용합니다. 연결 시간 제한은 기본 3초(`KBO_HTTP_CONNECT_TIMEOUT`), 요청 시간 제한은 기본 10초(`KBO_HTTP_REQUEST_TIMEOUT`)입니다. OAuth 서버 응답이 지연되어도 인증 토큰 발급이 무기한 대기하지 않습니다.

기기 토큰 무효 처리는 응답의 `error.details`에 FCM 오류 타입과 `UNREGISTERED`가 함께 있을 때 적용합니다. 일반 404, 잘못된 프로젝트 설정, HTML 오류 응답이나 JSON 파싱 실패만으로 기기를 비활성화하지 않습니다. 오류 형식은 [Firebase 공식 오류 코드 문서](https://firebase.google.com/docs/cloud-messaging/error-codes)를 따릅니다.

관련 회귀 테스트는 `FcmAccessTokenProviderTest`와 `FcmPushServiceTest`에서 요청 시간 제한, 실패 후 복구, 토큰 캐시, 중단 상태 유지, 오류 분류와 401 재시도 횟수를 확인합니다.

## 동기화 주기

~~~mermaid
stateDiagram-v2
    [*] --> Idle
    Idle --> Pregame: 경기 접근
    Pregame --> FastPregame: 시작 30분 전
    FastPregame --> Live: 경기 시작
    Live --> Finalizing: 종료 감지
    Finalizing --> Closed: 최종 상태 확인
    Idle: 30분
    Pregame: 5분
    FastPregame: 1분
    Live: 1초
    Finalizing: 30초
~~~

자동 동기화는 기본적으로 꺼져 있습니다.

| 환경 | 스키마 | Runtime role | 자동 동기화 |
| --- | --- | --- | --- |
| local, development | kbo_crawler_api_dev | 기본 reader | 기본 false |
| test | kbo_crawler_api_dev | 기본 reader | 기본 false |
| production | kbo_crawler_api | writer 명시 필요 | 명시적으로 활성화 |

잘못된 profile·schema·role 조합은 시작 단계에서 차단됩니다. APP_SYNC_ENABLED=true인 production writer는 production APNs 설정도 모두 유효해야 실행됩니다.

## 프로젝트 구조

~~~text
src/main/
├── java/com/kbo/crawlerapi/
│   ├── admin/          # 운영 콘솔과 세션 인증
│   ├── api/            # Public, internal, admin controller와 DTO
│   ├── config/         # 환경 설정, 보안 필터와 startup guard
│   ├── crawler/        # 공식 데이터 HTTP client
│   ├── domain/         # 경기, snapshot, 이벤트와 등록 토큰
│   ├── parser/         # 일정·상세·중계·박스스코어 parser
│   ├── repository/     # JPA/JDBC 저장소
│   ├── scheduler/      # 상태 적응형 동기화 scheduler
│   ├── service/        # 수집, 조회, 전달과 운영 서비스
│   └── support/        # hash, 입력 정규화와 팀 catalog
└── resources/
    ├── db/migration/   # Flyway 마이그레이션
    ├── templates/      # Thymeleaf 관리자 화면
    └── application-*.yml
~~~

## 로컬 실행

### 요구 사항

- JDK 17
- PostgreSQL
- 저장소에 포함된 Gradle Wrapper

### 1. 저장소 받기

~~~bash
git clone https://github.com/rudadlswh/ballCount_back.git
cd ballCount_back
~~~

### 2. 환경 설정

저장소 루트에 .env를 만들고 최소한 다음 값을 설정합니다.

~~~env
SPRING_PROFILES_ACTIVE=local
APP_DB_SCHEMA=kbo_crawler_api_dev
APP_RUNTIME_ROLE=reader
APP_SYNC_ENABLED=false

SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/postgres?currentSchema=kbo_crawler_api_dev
SPRING_DATASOURCE_USERNAME=postgres
SPRING_DATASOURCE_PASSWORD=your-password
~~~

로컬에서 동기화를 직접 검증할 때만 reader를 writer로 바꾸고 APP_SYNC_ENABLED=true를 사용합니다.

> .env, APNs .p8, FCM service-account JSON과 인증서는 Git에 커밋하지 마세요.

### 3. 실행

~~~bash
./gradlew bootRun
~~~

IntelliJ에서는 공유 실행 설정 `KboCrawlerApiApplication (Gradle)`을 선택하면 `local` 프로필로 실행됩니다. Gradle의 `bootRun`을 사용하면 최신 `application.yml`, Flyway 마이그레이션, Thymeleaf 템플릿이 실행 classpath에 포함됩니다. 실행 로그에 템플릿 경로 누락이나 소스보다 낮은 Flyway 최신 버전이 표시되면 `build/resources/main`이 빠진 Java 직접 실행 설정인지 확인하세요.

서버는 기본적으로 http://localhost:8088에서 실행됩니다.

~~~bash
curl http://localhost:8088/healthz
~~~

### 4. 테스트와 빌드

~~~bash
./gradlew test
./gradlew build
~~~

테스트 task는 test profile을 사용하며 production 스키마 접근과 자동 동기화를 차단합니다.

## Security and compatibility

- Set `ADMIN_API_KEY` outside Git. Internal and admin APIs require `X-Admin-Api-Key` (or `X-Admin-Key`);
  the admin web UI retains session login and CSRF protection. Both reconcile-stale
  aliases also require an admin key. An unset key denies API-key authentication. Configure trusted
  ingestion jobs to send the same header before deploying this change.
- The two `reconcile-stale` aliases accept at most three non-null dates, within
  the configured past window (default 7 days, hard maximum 366). One request
  is admitted per configured cooldown (default 3 minutes, minimum 30 seconds)
  per application instance, with at most one reconciliation running at a time. A busy/cooldown response is HTTP 429 with `Retry-After: 30`. Public
  calls respect detail-refresh TTL; administrators can still force refresh.
- Full APNs tokens authorize changes to their own registration. `installationId`
  and `activityId` are metadata and cannot replace or disable a different token.
  Same-token updates remain idempotent. Token rotation creates a new row; the
  existing invalid-token response handling retires old rows when APNs rejects them.
  The app ends replaced Live Activities itself. During that transition, old tokens
  are not disabled solely by an unproven installation ID.
- Apply Flyway V40/V41 with the application rollout. V40 permits separate rows for
  rotated device, Live Activity and push-to-start tokens while keeping full-token uniqueness; V41 supplies
  the snapshot and notification-setting columns required by Hibernate's default
  schema validation, preserving existing columns and their values.
- Spring Boot 4.0 uses patched Spring MVC 7.0. Existing API naming/date formats are
  preserved with Boot's Jackson 2 compatibility module and tested over HTTP.
  Keep `spring.http.converters.preferred-json-mapper=jackson2` until a separately
  tested Jackson 3 wire-format migration.

## API

### Public API

| Method | Endpoint | 설명 |
| --- | --- | --- |
| GET | /healthz | 프로세스 liveness |
| GET | /api/v1/games?date=2026-04-09 | 날짜별 경기 |
| GET | /api/v1/games/month?year=2026&month=4 | 월별 일정 |
| GET | /api/v1/games/{gameId} | 경기 요약 |
| GET | /api/v1/games/{gameId}/detail | 통합 경기 상세 |
| GET | /api/v1/games/{gameId}/live-state | 간결한 실시간 상태 |
| GET | /api/v1/games/{gameId}/linescore | 이닝별 점수 |
| GET | /api/v1/games/{gameId}/boxscore | 타자·투수 기록 |
| GET | /api/v1/games/{gameId}/lineup | 라인업 |
| GET | /api/v1/games/{gameId}/stream | SSE 실시간 스트림 |
| GET | /api/v1/scoreboard | 날짜별 스코어보드 |
| GET | /api/v1/teams | 팀 목록 |
| GET | /api/v1/standings?season=2026 | 시즌 순위 |
| GET/POST/DELETE | /api/v1/attendance | 설치 ID 기반 직관 기록 |

로컬·개발 환경에서는 다음 문서도 사용할 수 있습니다.

- Swagger UI: http://localhost:8088/swagger-ui.html
- OpenAPI JSON: http://localhost:8088/v3/api-docs
- OpenAPI YAML: http://localhost:8088/v3/api-docs.yaml

production에서는 Swagger와 OpenAPI endpoint가 비활성화됩니다.

<details>
<summary><strong>수동 수집 및 검증 endpoint</strong></summary>

Internal endpoint는 운영 앱용 public API가 아닙니다.

~~~bash
# 한 달의 공식 일정 수집
curl -X POST -H "X-Admin-Api-Key: ${ADMIN_API_KEY}" 'http://localhost:8088/internal/schedule/import?year=2026&month=4'

# 한 경기의 상세·라인스코어 수집
curl -X POST -H "X-Admin-Api-Key: ${ADMIN_API_KEY}" 'http://localhost:8088/internal/games/20260401-LG-KIA/detail/import'

# 같은 경기를 반복 갱신해 멱등성 확인
curl -X POST -H "X-Admin-Api-Key: ${ADMIN_API_KEY}" 'http://localhost:8088/internal/games/20260401-LG-KIA/detail/refresh?repeat=3'

# 실행 계획만 확인하거나 한 번의 orchestration pass 수행
curl -X POST -H "X-Admin-Api-Key: ${ADMIN_API_KEY}" 'http://localhost:8088/internal/orchestration/detail-refresh-pass?date=2026-04-09&execute=false'
curl -X POST -H "X-Admin-Api-Key: ${ADMIN_API_KEY}" 'http://localhost:8088/internal/orchestration/detail-refresh-pass?date=2026-04-09&execute=true'
~~~

원천 상태가 같으면 반복 실행 결과의 snapshotCreated와 lineScoresUpdated는 false가 됩니다.

</details>

## 실시간 스트림

SSE 구독 시 현재 최신 snapshot을 먼저 전송하고 이후 변경된 상태만 발행합니다.

- emitter timeout: 6시간
- heartbeat: 10초
- 종료 경기: 최종 event 전송 후 연결 정리
- registry: 현재 프로세스 메모리에 저장

따라서 현재 배포 구성은 **단일 백엔드 인스턴스**를 전제로 합니다. 수평 확장 전에는 공유 pub/sub 또는 외부 stream broker가 필요합니다.

Nginx 등 reverse proxy에서는 SSE buffering을 끄고 idle timeout을 heartbeat보다 길게 설정해야 합니다.

~~~nginx
location ~ ^/api/v1/games/[^/]+/stream$ {
    proxy_pass http://127.0.0.1:8088;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header Connection "";
    proxy_buffering off;
    proxy_cache off;
    proxy_read_timeout 6h;
    proxy_send_timeout 6h;
    add_header X-Accel-Buffering no always;
}
~~~

## 관리자 콘솔

Thymeleaf + HTMX 기반 운영 화면은 http://localhost:8088/admin에서 확인할 수 있습니다.

- **운영 관리자 페이지:** [https://kboscore-back.onrender.com/admin](https://kboscore-back.onrender.com/admin)
- **로컬 관리자 페이지:** [http://localhost:8088/admin](http://localhost:8088/admin)

~~~env
ADMIN_USERNAME=admin
ADMIN_PASSWORD=your-strong-password
ADMIN_SESSION_TIMEOUT=30m
ADMIN_SESSION_COOKIE_SECURE=false
ADMIN_STALE_GAME_THRESHOLD=2m
ADMIN_LOG_CAPACITY=2000
~~~

- 브라우저 session은 콘솔 조회와 일정 수집·수동 알림·로그아웃 폼에 사용됩니다. 로그인부터 모든 session 기반 변경 요청은 해당 session의 `_csrf` 토큰이 필요하며, 누락되거나 일치하지 않으면 `403`을 반환합니다. 로그인 성공 시 토큰을 갱신하므로 이전에 열어 둔 폼은 새로고침해야 합니다.
- 변경 가능한 /admin/crawl, /admin/ranks, /admin/test API는 별도의 X-Admin-Key 또는 X-Admin-Api-Key가 필요합니다.
- 사용자명과 비밀번호, DB URL과 secret은 화면에 노출하지 않습니다.
- 애플리케이션 로그는 메모리에 보관하며 검색 결과는 최대 500행입니다.

production HTTPS 환경에서는 ADMIN_SESSION_COOKIE_SECURE=true를 사용하세요.

## Database와 Flyway

- 개발·테스트 스키마: kbo_crawler_api_dev
- 운영 스키마: kbo_crawler_api
- Hibernate: ddl-auto=validate
- Flyway placeholder: ${appSchema}

한 번 적용된 versioned migration은 수정하지 않습니다. 스키마 변경은 항상 새 migration으로 추가하고 production 스키마 이름을 SQL에 직접 하드코딩하지 마세요.

~~~text
src/main/resources/db/migration/
└── V{version}__{description}.sql
~~~

public Supabase view는 앱의 read boundary입니다. migration이 table, view 또는 grant를 변경했다면 배포 전에 다음 SQL을 다시 확인합니다.

- [Public read policy](docs/supabase-public-read-policies.sql)
- [Security check](docs/supabase-security-check.sql)

## Docker 배포

production image는 Java 17 multi-stage build를 사용하고 UID/GID 10001의 non-root 사용자로 실행됩니다. Compose는 8088을 host loopback에만 공개하며 secret 디렉터리를 read-only로 mount합니다.

~~~bash
chmod 600 .env
mkdir -p secrets

docker compose config --quiet
docker compose build
docker compose up -d
docker compose ps
curl http://127.0.0.1:8088/healthz
~~~

APNs private key는 ./secrets에 두고 container 경로를 설정합니다.

~~~env
SPRING_PROFILES_ACTIVE=production
APP_DB_SCHEMA=kbo_crawler_api
APP_RUNTIME_ROLE=writer
APP_SYNC_ENABLED=true

SPRING_DATASOURCE_URL=jdbc:postgresql://host:5432/postgres?currentSchema=kbo_crawler_api
SPRING_DATASOURCE_USERNAME=your-user
SPRING_DATASOURCE_PASSWORD=your-password

KBO_PUSH_ENABLED=true
APNS_ENV=production
APNS_TEAM_ID=your-team-id
APNS_KEY_ID=your-key-id
APNS_BUNDLE_ID=com.chogm.kboScore
APNS_PRIVATE_KEY_PATH=/run/secrets/AuthKey_ABC123.p8
~~~

FCM을 활성화하는 경우 service-account JSON도 ./secrets에 두고 GOOGLE_APPLICATION_CREDENTIALS로 container 경로를 전달합니다. mount한 파일은 UID/GID 10001이 읽을 수 있어야 합니다.

> docker compose config 결과에는 .env 값이 포함될 수 있으므로 공개 로그나 이슈에 붙이지 마세요.

## CI/CD

~~~mermaid
flowchart LR
    Push["Push to main"] --> Test["Gradle Test"]
    Test --> Image["amd64 / arm64 Image"]
    Image --> GHCR["Push to GHCR"]
    GHCR --> Deploy["SSH Deploy"]
    Deploy --> Health["/healthz · 최대 5분 확인"]
~~~

GitHub Actions는 테스트 성공 후 linux/amd64, linux/arm64 이미지를 GHCR에 게시합니다. 실제 SSH 배포는 repository variable PRODUCTION_DEPLOY_ENABLED=true일 때만 실행되며, 배포 스크립트는 최대 5분 동안 /healthz를 확인합니다.

## 운영 안전 규칙

- production port 8088은 외부에 직접 공개하지 않고 HTTPS reverse proxy 뒤에 둡니다.
- production writer는 하나만 실행해 중복 수집과 알림을 방지합니다.
- .env, DB credential, admin key, APNs·FCM credential은 저장소 밖에서 관리합니다.
- APNs key가 노출됐을 가능성이 있으면 즉시 폐기하고 새 key로 교체합니다.
- reader 또는 role 미지정 환경에서는 APP_SYNC_ENABLED=false를 유지합니다.
- production 배포 후에는 /healthz, 관리자 작업 상태와 실패 로그를 확인합니다.

## 관련 문서

- [게임 식별자와 점수 저장 ADR](docs/adr/0001-game-identity-and-score-storage.md)
- [Supabase public read 정책](docs/supabase-public-read-policies.sql)
- [Supabase 보안 점검 SQL](docs/supabase-security-check.sql)
- [iOS 앱 저장소](https://github.com/rudadlswh/ballCount)

## 시즌·라운드 분류

V37은 `games.season_type`, `games.series_id`와 공개 뷰의 동일 필드를 추가합니다. 공식 시리즈별 일정 조회 결과를 저장하며, 월간·날짜별·경기 상세 API에는 `seasonClassification`, `seriesId`, 호환 bootstrap에는 `season_classification`, `series_id`를 제공합니다.

기존 행은 날짜로 추정하지 않고 `unknown`으로 유지합니다. 마이그레이션 배포 후 필요한 과거 월을 인증된 `POST /internal/schedule/import?year=2026&month=10`으로 재수집하면 기존 경기 UUID를 유지한 채 분류가 채워집니다. 정규시즌 순위 재계산 전에는 해당 시즌 전체 월의 분류를 채워야 합니다. 미분류 종료 경기가 있으면 기존 순위를 보존하고 재계산을 실패시킵니다. 포스트시즌·시범경기·올스타전은 정규시즌 순위에 포함하지 않습니다.

분류 없는 응답으로 재수집해도 이미 저장한 시리즈는 지우지 않습니다. 공식 일정이 없는 시리즈의 빈 `rows`는 정상 응답이며, 잘못된 형식이나 상충하는 시리즈는 저장 전에 실패합니다. 현재 월 수집은 시리즈 7개를 각각 요청합니다.

실제 PostgreSQL 저장 검증은 운영 DB와 분리된 로컬 DB에서 실행합니다. `KBO_SEASON_TEST_DATABASE_URL=jdbc:postgresql://127.0.0.1:55438/postgres ./gradlew test --tests '*GameSeasonPersistenceTest'`는 `codex` 로컬 사용자로 임시 스키마를 생성·삭제합니다.

## 일정 발견과 알림 복구

자동 동기화가 켜진 writer는 DB에 오늘 경기가 없어도 현재 월과 다음 월의 공식 일정을 시작 후 5초에 조회하고, 이후 성공 시각 기준 30분마다 갱신합니다. 실패하거나 날짜 잠금을 얻지 못하면 1분 뒤 재시도합니다. 기존 실시간 스케줄러는 빈 일정과 전체 종료 상태에서 1분마다 DB를 확인하므로, 새로 추가한 경기를 감지할 수 있습니다.

공식 경기 ID가 다른 더블헤더는 서로 다른 행으로 저장합니다. 새 공개 ID는 `날짜-홈팀-원정팀-차수` 형식이며, 이미 저장한 공개 ID와 UUID는 유지합니다. 공식 ID가 없는 취소 행의 대상이 여러 경기라면 임의로 갱신하지 않습니다. 과거에 합쳐진 경기의 스냅샷·이벤트는 원천 대조 없이 자동 분리하지 않습니다.

V38은 비공개 `notification_outbox`, `notification_deliveries` 테이블을 추가합니다. 이벤트와 발송 대상 기기를 같은 트랜잭션으로 저장하고, 커밋 후 전송합니다. 메모리 큐가 가득 차거나 서버가 재시작해도 writer가 5초마다 DB 작업을 다시 찾습니다. 이벤트는 한 worker가 60초의 갱신 가능한 임대로 점유하며, 저장된 기기별 성공 결과는 재전송하지 않습니다. 실패 작업은 30초 간격으로 최대 5회 시도하고 생성 후 5분이 지나면 만료합니다. 재시도 시 현재 알림 설정도 다시 적용합니다.

전송 서버가 성공했지만 응답 확인·DB 기록 전에 프로세스가 종료되면, 수신 여부를 확정할 수 없어 중복 전달될 가능성은 남습니다. V38 이전 이벤트에는 기기별 기록이 없으므로 과거 실패·부분 성공 이벤트를 자동 재발송하지 않습니다.

여러 writer를 운영하기 전에는 `KBO_LIVE_SYNC_LOCK_MODE=postgres-session`과 세션을 유지하는 PostgreSQL 연결을 사용해야 합니다. 이 모드에서 DB 잠금 획득에 실패하면 실행을 건너뜁니다. 메모리 잠금으로 대체하지 않습니다.

## 데이터 자동 정리

V39 배포 후 `APP_SYNC_ENABLED=true`인 writer는 매분 보관 기간이 지난 데이터를 정리합니다. `DATA_RETENTION_ENABLED=false`로 중지할 수 있습니다. 첫 실행부터 기존 데이터에도 적용됩니다.

| 대상 | 삭제 기준 |
| --- | --- |
| `game_events` | 경기 종료 확정과 이벤트 마지막 갱신 후 각각 3주가 지난 경우 |
| `live_activity_tokens` | 한국 시간 경기 다음날 08시 이후, 종료가 확정된 경기의 비활성 토큰 |
| `notification_outbox`, `notification_deliveries` | 발송 작업 만료 시각으로부터 1주가 지난 완료·만료 작업과 그 기기별 기록 |
| `notification_events` | 경기 종료 확정 및 이벤트 생성·마지막 전송 후 각각 1주가 지난 처리 완료 기록 |
| `crawl_jobs`, `crawl_failures` | 완료 작업의 종료·갱신 및 실패 발생 후 각각 달력 기준 2개월이 지난 기록 |

발송 대기·처리 중이거나 임대가 유효한 알림 작업, 활성 Live Activity 토큰, 연결 경기를 확인할 수 없거나 식별자가 상충하는 토큰은 보존합니다. 최근 실패 기록이 연결된 수집 작업도 보존합니다. 이 조건 때문에 실제 삭제 시점은 위 기간보다 늦어질 수 있습니다. 취소·연기 경기와 종료 미확정 경기의 이벤트·토큰·알림 기록은 자동 삭제하지 않습니다.

알림 본문을 지워도 과거 경기 재수집으로 같은 알림이 재발송되지 않도록, 비공개 `notification_event_keys`에는 중복 확인용 `event_key`만 계속 보존합니다. 알림 기기 등록, push-to-start 토큰, 경기 결과, 스냅샷, 이닝별 점수, 선수 기록은 이 정리 대상에 포함되지 않습니다. 3주가 지난 경기의 문자 중계와 1주가 지난 알림 상세 내역은 조회할 수 없게 됩니다.

한 트랜잭션에서 대상별 최대 1,000건을 삭제하고 다음 분에 이어서 처리합니다. DB 잠금으로 중복 실행을 막고 다른 작업이 잠근 행은 건너뜁니다. 실패 시 해당 실행 전체를 롤백하며 다음 분에 다시 시도합니다. 삭제 건수는 서버 로그의 `[DataRetention]`으로 확인합니다(연쇄 삭제된 기기별 전송 기록 수는 별도 집계하지 않음).

실제 PostgreSQL 검증은 별도 로컬 DB에서 `KBO_RETENTION_TEST_DATABASE_URL=jdbc:postgresql://127.0.0.1:55479/postgres ./gradlew test --tests '*DataRetention*'`로 실행합니다. `codex` 로컬 사용자로 테스트용 스키마를 생성·삭제하며 운영 DB를 사용하지 않습니다.
