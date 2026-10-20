# Security Policy

## Supported versions

Bromo is pre-1.0 and under active restructuring. Only the `main` branch receives security fixes.

## Reporting a vulnerability

Do not open a public issue for a security problem.

Use GitHub private vulnerability reporting on this repository: open the **Security** tab and choose **Report a
vulnerability**. Include what you found, how to reproduce it, and the impact.

You can expect an acknowledgement within a reasonable time for a personal project and a fix or mitigation plan once the
report is confirmed. Please allow time to fix the issue before public disclosure.

## Scope

In scope: the ingest, read, and chat APIs, authentication and rate limiting, the AI agent boundaries (read-only access,
budgets, prompt injection handling), and the deployment configuration in this repository.

Out of scope: vulnerabilities in third-party software (ClickHouse, LLM providers) that are not caused by our
configuration, and attacks that need access to the host.

## Security design summary

- Ingest needs an API key. Chat needs a token, a rate limit, and a daily spend cap.
- The agent connects to ClickHouse with a read-only user and per-query limits. Write access exists only for the findings table, through a separate user.
- Event content is treated as data in prompts.
- Secrets live in environment files outside git. Logs must not contain secrets.

Details: [docs/AGENT.md](docs/AGENT.md#5-safety) and [AGENTS.md](AGENTS.md#security).
