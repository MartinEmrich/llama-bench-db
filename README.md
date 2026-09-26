# llama-bench-db

Organize results of your [llama-bench](https://github.com/ggml-org/llama.cpp) runs, across your systems and models!

![Screenshot of the first one-shot version, showing the result entry/view page.](/doc/screenshot-oneshot.png?raw=true "Screenshot of llama-bench-db")

_(Vibe-coded using open weights models on local hardware!)_

## Configuration

Environment variables (all optional; defaults shown):

| Variable | Default | Meaning |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | _(unset — local H2 file DB at `./data/llamabendb`)_ | `postgres` or `mariadb` to use an external database |
| `DB_URL` | `jdbc:postgresql://localhost:5432/llamabendb` (postgres) / `jdbc:mariadb://localhost:3306/llamabendb` (mariadb) | JDBC URL of the external database |
| `DB_USER` | `llamabendb` | Database user |
| `DB_PASSWORD` | _(empty)_ | Database password |

`DB_*` are only used with the postgres/mariadb profiles. Other Spring Boot properties can be set the usual way (e.g. `SERVER_PORT`).

## Docker

Build the boot jar first (`./gradlew bootJar`), then from the repo root:

    docker build -t llama-bench-db .
    docker run --rm -p 8080:8080 \
        -e DB_URL=jdbc:postgresql://host:5432/llamabendb \
        -e DB_USER=llamabendb \
        -e DB_PASSWORD=... \
        llama-bench-db

The image starts with the postgres profile by default (override with `SPRING_PROFILES_ACTIVE`).
