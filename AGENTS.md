# AGENTS.md

Spring Boot 4.1.1 (Java 25, Gradle 9.7.1 wrapper, single module) + Nuxt 4 SPA in `frontend/`. Stores and organizes llama.cpp `llama-bench` benchmark results across computers, models, and run configurations so runs can be compared and filtered. The HTTP API under `/api` is intentionally plain JSON and script-friendly (not strictly RESTful). Read `TODO.md` for locked decisions, the schema strategy, and open work before making changes.

## Commands

- Backend dev: `./gradlew bootRun` — port 8080, H2 file DB at `./data/llamabendb` (gitignored; data persists between runs).
- Tests: `./gradlew test`; single class: `./gradlew test --tests "llamabendb.importer.ImportParserTest"`. Integration tests boot the full app against in-memory H2.
- Frontend dev: `npm run dev` in `frontend/` — its Vite proxy forwards `/api` to **localhost:8081**, so run the backend on 8081 for frontend dev (e.g. `./gradlew bootRun --args='--server.port=8081'`).
- Full jar with UI: `./gradlew build` (or `bootJar`) — Gradle builds the frontend automatically (`npmInstall` + `nuxtBuild`, which runs `nuxt build` and `scripts/gen-entry.mjs` to produce `frontend/dist/`). Requires Node.js >= 20 and npm on PATH.
- Other DBs: `--spring.profiles.active=postgres|mariadb`, env `DB_URL` / `DB_USER` / `DB_PASSWORD`.

## Domain model

- **Computer** (`domain/Computer.java`): one physical system; `name` + optional `hostname`. Versioned by timestamp — a hardware change adds a `ComputerVersion` (with description). Results link to the **version**, not the computer.
- **Model** (`domain/Model.java`): `name` (base name), `modelId` (Hugging Face repo `uploader/name`), `quantization`, optional `sizeGiB`. Full HF ids like `unsloth/Qwen3.5-4B:Q4_K_M` are parsed by `domain/HfModelId.java` (base name = repo without uploader and trailing `-GGUF`).
- **Result** (`domain/Result.java`): one benchmark run, linked to a computer version + model. One result = one pair of llama-bench table rows — `pp<tokens>` and `tg<tokens>` (stored as pp/tg tokens, t/s, deviation); the importer rejects tables with a missing or duplicated pp/tg row. Run parameters default when absent from the paste: `ngl=-1` (all layers), `type_k/type_v=f16`, `fa=false`, `load_mode=auto`. Observed size is cross-checked against the model's stored size; >10% difference is rejected (`service/ImportService.java`).
- Quantization ordering uses `domain/QuantSortKey.java`: bit count, then size class (XXS < XS < S < M < L), then family — e.g. IQ3_XXS < Q4_0 < Q5_K_M.

## Gotchas

- Flyway migrations are per-dialect in `src/main/resources/db/migration/{h2,postgres,mariadb}/`. A new migration must be added to **all three** directories with the same version number.
- Schema strategy (see TODO.md): typed columns for stable fields; unrecognized llama-bench parameters land in the JSON `params` column on Result (`Map<String,Object>` + `@JdbcTypeCode(SqlTypes.JSON)` — Hibernate 7 removed `JsonType`). When an attribute earns filter/sort status it is **promoted**: a Flyway migration adds the column and backfills it from the JSON, then the importer writes the real column.
- Import parsing is server-side (`importer/ImportParser.java`); parser test fixtures are sanitized transcripts in `src/test/resources/samples/`.
- `llama-bench-help.txt` (repo root) is the canonical llama-bench parameter list — needed for Phase 8 parameter normalization.
- `local_samples/` holds real transcripts for manually testing import; gitignored, never commit it.
- Frontend build artifacts (`frontend/node_modules/`, `frontend/.output/`, `frontend/dist/`) are gitignored and produced by the Gradle tasks `npmInstall`/`nuxtBuild`. Delete them to force a full frontend rebuild; gen-entry binds port 39871 while running.
- No lint, formatter, typecheck, or CI is configured; `./gradlew test` is the only automated check.
