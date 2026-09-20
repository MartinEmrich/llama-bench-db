# TODO — llama-bench-db implementation plan

See `PLAN.md` for requirements. This file tracks the build; check items off as they land.

## Locked decisions

- **Stack:** Spring Boot 4.1.1 (Hibernate ORM 7.4) · Java 25 (Gradle toolchain) · Gradle 9.7.1 wrapper at repo root, single module · JSON column via `@JdbcTypeCode(SqlTypes.JSON)` on a `Map<String,Object>` (Hibernate 7 removed `JsonType`) · Spring Data JPA · Flyway with per-dialect locations · H2 file DB local / MariaDB / PostgreSQL — same app, no code changes · Nuxt SPA in `frontend/` · JUnit Jupiter via starter-test
- **Schema strategy:** typed columns for known/stable fields; unrecognized llama-bench parameters go into a JSON map column on Result. When an attribute earns filter/sort status it gets **promoted**: Flyway migration adds the column + backfills it from the JSON, importer writes the real column (blob cleanup skipped)
- **Import:** strict single-model paste, server-side parsing, no auth
- **Layout:** Gradle project at repo root; Nuxt in `frontend/`

## Phase 0 — Build setup

- [x] `settings.gradle`: drop `include('app')` (init leftover); keep foojay-resolver plugin + `rootProject.name`
- [x] Write `build.gradle.kts`: Boot 4.1.1 plugin, Java toolchain 25, deps: spring-boot-starter-web, spring-boot-starter-data-jpa, flyway-core, H2 (runtime), PostgreSQL + MariaDB drivers (runtime), spring-boot-starter-test
- [x] Extend `.gitignore`: `data/`, `node_modules/`, `.nuxt/`, `dist/`, IDE dirs
- [x] Slim `gradle/libs.versions.toml` (drop guava)
- [x] Gradle task `copyFrontendDist`: copy `frontend/dist/` into the jar's static resources at packaging time (skip with log message if absent); wire into `bootJar`. Gradle never invokes npm — you run `npm run build` in `frontend/` yourself. (Note: Boot 4's `bootJar` does not depend on the plain `jar` task, so the copy is wired into `processResources` instead → lands in `BOOT-INF/classes/static/`.)
- [x] Gate: `./gradlew build` green

## Phase 1 — App skeleton + configuration

- [x] Main application class (base package `llamabendb`)
- [x] `application.yml`: default H2 file DB at `./data/`; postgres/mariadb profiles via env vars; in-memory H2 for tests
- [x] Flyway wiring, per-dialect locations `db/migration/{h2,postgres,mariadb}` (identical DDL except the JSON column type: `JSON` / `jsonb` / `JSON`)
- [x] SPA deep-link forward: extensionless non-API GETs → `index.html`, so client-side routes survive refresh; unknown `/api/**` paths still 404 as JSON

## Phase 2 — Domain model + V1 migration

- [x] **Computer** (id, name) + **ComputerVersion** (id, computer FK, timestamp, description) — append-only; results reference the exact version row
- [x] **Model** (id, name, modelid unique, quantization, size_gib nullable, quant_sort_key) — sort key derived at write time: zero-padded `bits|size-class-rank|family|raw`; unparseable quants (e.g. PTQ1_0) fall back to end-of-order
- [x] **Result** (id, computer_version FK, model FK, imported_at, model_string, size_gib_observed, backend, devices [from the table's `dev` column], ngl [default -1; ≥99 stored as-is, mapped at query time], type_k/type_v [default f16], fa [default off], threads, ts [as-parsed string], load_mode [auto/mmap/none], pp_tokens, tg_tokens, pp_tps, tg_tps, pp_deviation, tg_deviation, params JSON map)
- [x] Repositories for all entities

## Phase 3 — Importer

- [x] Noise-tolerant table-block scanner: find contiguous `|`-line blocks in raw console transcripts; ignore command lines, ggml device dumps, download progress, error lines, `^C`/terminal escapes, `---` separators
- [x] Header-name-driven parsing (never positional); column sets vary per build
- [x] Empty tables (header + separator only) skipped silently
- [x] Auto-grouping: rows sharing all parameter columns form one dataset; each dataset needs exactly one `pp*` and one `tg*` row, else error
- [x] **Strict single-model rule:** paste with >1 distinct model string → reject with an error listing the distinct strings
- [x] Normalization: ngl absent → -1; type_k/type_v absent → f16; fa absent → off; lm/mmap → load_mode (auto/mmap/none); ts split on `/` or `;`; size MiB/GiB → GiB float
- [x] Cross-checks: model size empty → fill Model row; >10% difference → block until re-submit with acknowledge flag; table backend vs devices → non-blocking warning
- [x] Stretch: capture the trailing `build: <commit>` line per table — implemented as a promoted typed column `result.build` (Flyway V2, positional attribution to the preceding table) instead of params JSON; import form prefills it from `GET /api/results/latest-build?computerId=&backend=` when the paste has no build line

## Phase 4 — API (pragmatic JSON, no auth)

- [x] `GET/POST /api/computers`, `PUT/DELETE /api/computers/{id}`
- [x] `GET/POST /api/computers/{id}/versions` (new version = new row with timestamp + description)
- [x] `GET/POST /api/models` (create accepts a full HF id → parsed into fields), `PUT/DELETE /api/models/{id}`
- [x] `GET /api/results` — filters: computer, model, quantization, pp/tg tokens, tps ranges; sortable by all listed columns incl. quant sort key; paginated
- [x] `POST /api/results/import` `{computerId, versionId, modelId, text, acknowledgeWarnings?}` → created datasets + warnings; 422 on multi-model paste or malformed input
- [x] `GET /api/export`, `POST /api/import` — full JSON dump/restore for backend migration

## Phase 5 — Frontend (Nuxt, SPA mode)

- [x] Scaffold in `frontend/`, dev proxy to the backend port. (Note: Nuxt 4's `devProxy` is a nitro option and does not apply to the Vite SPA fallback — the proxy lives in `vite.server.proxy`.)
- [x] **Results** (main page): paginated table, filter bar, sortable columns
- [x] **Import form:** computer + version select, model select, paste field; warning/confirm dialog for size mismatch; error display
- [x] **Computers:** list/detail with version history, add-version action, CRUD
- [x] **Models:** list, create form with HF-id parsing, CRUD
- [x] Minimal hand-rolled CSS, no UI framework
- [x] Single-process run: jar serves API + built frontend on one port; dev stays two-process (Nuxt dev server :3000 proxying `/api` → :8080); external-dist option via `--spring.web.resources.static-locations=file:frontend/dist/,classpath:/static/` (no custom code). (Note: Nuxt 4 with `ssr:false` emits no static `index.html` — `frontend/scripts/gen-entry.mjs` starts the built nitro server after `nuxt build`, fetches `/`, and writes `dist/index.html` + `_nuxt/`.)

## Phase 6 — Tests

- [x] Sanitized parser fixtures in `src/test/resources/samples/` (fantasy model names, loaded via classpath): `test-multi-model-sections.txt` (multi-model rejection + three `---` sections as single-model fixtures), `test-multi-model-rejection.txt`, `test-noise-and-unknown-columns.txt` (empty tables, `^C`/error noise, unknown columns, build lines), `test-two-datasets-one-paste.txt`
- [x] Synthetic malformed inputs: missing tg row, inconsistent rows within a group, garbage-only paste, MiB sizes, `^C` noise
- [x] Repository + API integration tests on in-memory H2

## Phase 7 — Verification

- [x] End-to-end import of every sample section via the API
- [x] Filter/sort sanity checks (quantization ordering, tps ranges)
- [x] Standalone + PostgreSQL smoke test (PG 18.6): clean build, Flyway migration applied, Hibernate validate passed, import + persistence across restarts. Required `org.flywaydb:flyway-database-postgresql` runtime dep — Flyway ≥10 needs the database-specific module
- [ ] MariaDB smoke test — scrapped for now (if revisited: same pattern, the module is `org.flywaydb:flyway-mysql`)

## Phase 8 — Later: parameter normalization (planned, not started)

Background: llama-bench parameters appear in three spellings — CLI short form (`-ncmoe`), CLI long form (`--n-cpu-moe`) and a table-header variant (`n_cpu_moe`). The full parameter list is captured in `llama-bench-help.txt` (repo root). Unknown parameters currently pass through into the `params` JSON as-is, which is the intended fallback.

- [ ] Canonical parameter map: explicitly enumerate all known llama-bench parameters from `llama-bench-help.txt` — short form / long form / table-header variant → one canonical name; unknown parameters keep the as-is fallback
- [ ] Special semantics for the n_cpu_moe family: `-ncmoe/--n-cpu-moe <n>` (exact expert count) vs `cmoe`/`cpu-moe` ("all") — treat as one logical attribute with distinct value semantics; decide normalization/storage when implemented
