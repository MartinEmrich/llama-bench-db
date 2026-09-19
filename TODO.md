# TODO — llama-bench-db implementation plan

See `PLAN.md` for requirements. This file tracks the build; check items off as they land.

## Locked decisions

- **Stack:** Spring Boot 4.1.1 · Java 25 (Gradle toolchain) · Gradle 9.7.1 wrapper at repo root, single module · Hibernate via Boot (`JsonType` for JSON columns) · Spring Data JPA · Flyway with per-dialect locations · H2 file DB local / MariaDB / PostgreSQL — same app, no code changes · Nuxt SPA in `frontend/` · JUnit Jupiter via starter-test
- **Schema strategy:** typed columns for known/stable fields; unrecognized llama-bench parameters go into a JSON map column on Result. When an attribute earns filter/sort status it gets **promoted**: Flyway migration adds the column + backfills it from the JSON, importer writes the real column (blob cleanup skipped)
- **Import:** strict single-model paste, server-side parsing, no auth
- **Layout:** Gradle project at repo root; Nuxt in `frontend/`

## Phase 0 — Build setup

- [ ] `settings.gradle`: drop `include('app')` (init leftover); keep foojay-resolver plugin + `rootProject.name`
- [ ] Write `build.gradle.kts`: Boot 4.1.1 plugin, Java toolchain 25, deps: spring-boot-starter-web, spring-boot-starter-data-jpa, flyway-core, H2 (runtime), PostgreSQL + MariaDB drivers (runtime), spring-boot-starter-test
- [ ] Extend `.gitignore`: `data/`, `node_modules/`, `.nuxt/`, `dist/`, IDE dirs
- [ ] Slim `gradle/libs.versions.toml` (drop guava)
- [ ] Gate: `./gradlew build` green

## Phase 1 — App skeleton + configuration

- [ ] Main application class (base package `llamabendb`)
- [ ] `application.yml`: default H2 file DB at `./data/`; postgres/mariadb profiles via env vars; in-memory H2 for tests
- [ ] Flyway wiring, per-dialect locations `db/migration/{h2,postgres,mariadb}` (identical DDL except the JSON column type: `JSON` / `jsonb` / `JSON`)

## Phase 2 — Domain model + V1 migration

- [ ] **Computer** (id, name) + **ComputerVersion** (id, computer FK, timestamp, description) — append-only; results reference the exact version row
- [ ] **Model** (id, name, modelid unique, quantization, size_gib nullable, quant_sort_key) — sort key derived at write time: zero-padded `bits|size-class-rank|family|raw`; unparseable quants (e.g. PTQ1_0) fall back to end-of-order
- [ ] **Result** (id, computer_version FK, model FK, imported_at, model_string, size_gib_observed, backend, devices [from the table's `dev` column], ngl [default -1; ≥99 stored as-is, mapped at query time], type_k/type_v [default f16], fa [default off], threads, ts [as-parsed string], load_mode [auto/mmap/none], pp_tokens, tg_tokens, pp_tps, tg_tps, pp_deviation, tg_deviation, params JSON map)
- [ ] Repositories for all entities

## Phase 3 — Importer

- [ ] Noise-tolerant table-block scanner: find contiguous `|`-line blocks in raw console transcripts; ignore command lines, ggml device dumps, download progress, error lines, `^C`/terminal escapes, `---` separators
- [ ] Header-name-driven parsing (never positional); column sets vary per build
- [ ] Empty tables (header + separator only) skipped silently
- [ ] Auto-grouping: rows sharing all parameter columns form one dataset; each dataset needs exactly one `pp*` and one `tg*` row, else error
- [ ] **Strict single-model rule:** paste with >1 distinct model string → reject with an error listing the distinct strings
- [ ] Normalization: ngl absent → -1; type_k/type_v absent → f16; fa absent → off; lm/mmap → load_mode (auto/mmap/none); ts split on `/` or `;`; size MiB/GiB → GiB float
- [ ] Cross-checks: model size empty → fill Model row; >10% difference → block until re-submit with acknowledge flag; table backend vs devices → non-blocking warning
- [ ] Stretch: capture the trailing `build: <commit>` line per table into params JSON

## Phase 4 — API (pragmatic JSON, no auth)

- [ ] `GET/POST /api/computers`, `PUT/DELETE /api/computers/{id}`
- [ ] `GET/POST /api/computers/{id}/versions` (new version = new row with timestamp + description)
- [ ] `GET/POST /api/models` (create accepts a full HF id → parsed into fields), `PUT/DELETE /api/models/{id}`
- [ ] `GET /api/results` — filters: computer, model, quantization, pp/tg tokens, tps ranges; sortable by all listed columns incl. quant sort key; paginated
- [ ] `POST /api/results/import` `{computerId, versionId, modelId, text, acknowledgeWarnings?}` → created datasets + warnings; 422 on multi-model paste or malformed input
- [ ] `GET /api/export`, `POST /api/import` — full JSON dump/restore for backend migration

## Phase 5 — Frontend (Nuxt, SPA mode)

- [ ] Scaffold in `frontend/`, dev proxy to the backend port
- [ ] **Results** (main page): paginated table, filter bar, sortable columns
- [ ] **Import form:** computer + version select, model select, paste field; warning/confirm dialog for size mismatch; error display
- [ ] **Computers:** list/detail with version history, add-version action, CRUD
- [ ] **Models:** list, create form with HF-id parsing, CRUD
- [ ] Minimal hand-rolled CSS, no UI framework

## Phase 6 — Tests

- [ ] Parser fixtures from `samples/`: all four files; surfacego.txt as the multi-model rejection case; its three sections (split at `---`) as valid single-model fixtures
- [ ] Synthetic malformed inputs: missing tg row, inconsistent rows within a group, garbage-only paste, MiB sizes, `^C` noise
- [ ] Repository + API integration tests on in-memory H2

## Phase 7 — Verification

- [ ] End-to-end import of every sample section via the API
- [ ] Filter/sort sanity checks (quantization ordering, tps ranges)
- [ ] Optional: Postgres smoke test
