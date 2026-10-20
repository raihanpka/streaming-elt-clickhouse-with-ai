# Contributing to Bromo

Thank you for your interest. Bromo is a personal open source project with a deliberately small scope. Please read this before opening an issue or pull request.

## Before you start

1. Read the [README](README.md) and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
2. Read [AGENTS.md](AGENTS.md). It is the rulebook for humans and AI agents alike.
3. Check the roadmap in the README. Work outside it may be declined as out of scope. Open an issue to discuss it first.

## Ways to help

- Report a bug with a minimal reproduction.
- Improve documentation, especially anything that is wrong or unclear.
- Add a dataset adapter for the replayer (with a download script and a license note).
- Add tests for existing behavior.

## Development setup

Prerequisites: JDK 21+, Python 3.12+, Docker Engine 25+.

```bash
git clone https://github.com/raihanpka/streaming-elt-clickhouse-with-ai
cd streaming-elt-clickhouse-with-ai
cp .env.example .env
docker compose -f infra/docker/docker-compose.yml up -d
./gradlew build
```

Python components appear as the plan progresses. Their commands are listed in [AGENTS.md](AGENTS.md#commands).

## Pull requests

- One logical change per pull request. Keep it small enough to review in one sitting.
- Branch from `main`. Use a descriptive branch name such as `feat/ingest-size-limit`.
- The build must be green: `./gradlew build`, and for Python `ruff check`, `mypy`, and `pytest`.
- Add or update tests for behavior you change. Add or update docs in the same pull request, including
  [docs/API.md](docs/API.md) for endpoint changes and [docs/OPERATIONS.md](docs/OPERATIONS.md) plus
  `.env.example` for new variables.
- Do not add dependencies without discussing them in the pull request description.
- Do not commit secrets, datasets, or build output.

### Commit messages

Single line, `type(scope): summary`, no body.

Types: `feat`, `fix`, `refactor`, `perf`, `test`, `docs`, `build`, `ci`, `chore`.
Scopes: `ingest`, `pipeline`, `metrics`, `findings`, `chat`, `agent`, `warehouse`, `replayer`, `ui`, `infra`, `docs`.

Example: `feat(ingest): reject events above the size limit`

### Style

- Java: Google Java Style via Spotless (`./gradlew spotlessApply`). Vertical slices. See the rules in
  [AGENTS.md](AGENTS.md#java-architecture-vertical-slices).
- Python: `ruff` for lint and format, type hints, `mypy`.
- Write in English. Do not use dashes as punctuation (em dash, en dash, spaced hyphen) or emojis anywhere.
- One short intent comment per function or method.

## Reporting issues

Use the issue templates. Include the version or commit, what you expected, what happened, and how to reproduce it. For
security problems, do not open a public issue. Follow [SECURITY.md](SECURITY.md).

## Code of conduct

By participating you agree to the [Code of Conduct](CODE_OF_CONDUCT.md).

## License

By contributing, you agree that your contributions are licensed under the [MIT License](LICENSE).
