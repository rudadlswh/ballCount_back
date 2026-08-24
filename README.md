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
curl -X POST 'http://localhost:8088/internal/schedule/import?year=2026&month=4'

# 한 경기의 상세·라인스코어 수집
curl -X POST 'http://localhost:8088/internal/games/20260401-LG-KIA/detail/import'

# 같은 경기를 반복 갱신해 멱등성 확인
curl -X POST 'http://localhost:8088/internal/games/20260401-LG-KIA/detail/refresh?repeat=3'

# 실행 계획만 확인하거나 한 번의 orchestration pass 수행
curl -X POST 'http://localhost:8088/internal/orchestration/detail-refresh-pass?date=2026-04-09&execute=false'
curl -X POST 'http://localhost:8088/internal/orchestration/detail-refresh-pass?date=2026-04-09&execute=true'
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

~~~env
ADMIN_USERNAME=admin
ADMIN_PASSWORD=your-strong-password
ADMIN_SESSION_TIMEOUT=30m
ADMIN_SESSION_COOKIE_SECURE=false
ADMIN_STALE_GAME_THRESHOLD=2m
ADMIN_LOG_CAPACITY=2000
~~~

- 브라우저 session은 read-only 콘솔 경로에만 사용됩니다.
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
    Push[Push to main] --> Test[Gradle Test]
    Test --> Image[amd64 / arm64 Image]
    Image --> GHCR[Push to GHCR]
    GHCR --> Deploy[SSH Deploy]
    Deploy --> Health[/healthz up to 5 min]
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
