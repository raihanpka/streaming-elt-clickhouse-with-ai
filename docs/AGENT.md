# AI Agent

The agent detects anomalies and explains data and anomalies in the event stream. It is read only. It never changes business data.

## 1. Division of work

| Step | Done by | Why |
|---|---|---|
| Find candidate anomalies | Detector (statistics) | Cheap, fast, testable, no LLM cost per event |
| Investigate and explain | LLM agent with read-only SQL tools | Needs judgment over several queries |
| Store the result | Findings writer | Separate write path, no extra rights |
| Explain data on request | Same agent through the chat gateway, optionally with the chart a user selected as context | One safety model for both |
| Answer user questions | Same agent through the chat gateway | One safety model for both |

"Real time" here means events are queryable within seconds and the agent reacts within one detection interval. The LLM
is not called once per event.

## 2. Detector

- Input: the gold tables, per `(type, source)` series and per metric in the data contract.
- Method: robust z-score using median and MAD over a rolling window, evaluated every `DETECT_INTERVAL_SECONDS`.
- Output: a candidate with metric, dimension, window, and score.
- Candidates below the threshold are dropped. Candidates that repeat an open finding are suppressed.
- All scoring functions are pure and tested on fixtures, one per injected anomaly type.

## 3. Agent loop

1. Receive a candidate or a chat question.
2. Build the prompt: task, metric registry, table `COMMENT`s, and the candidate. Event content is quoted as data.
3. Call the model in a tool loop. Allowed tools:
   - `list_databases`, `list_tables`, `run_select_query` from the open-source `mcp-clickhouse` server.
   - No other tool. There is no write tool, no HTTP tool, no file tool.
4. Stop at a final structured answer or when a budget is reached.

### Budgets

| Limit | Setting | Default |
|---|---|---|
| Tool calls per run | `AGENT_MAX_TOOL_CALLS` | 8 |
| Tokens per run | `AGENT_MAX_TOKENS` | 20000 |
| Wall time per run | `AGENT_TIMEOUT_SECONDS` | 60 |
| Daily spend | `BROMO_LLM_DAILY_BUDGET_USD` (enforced in `bromo-app`) | none, must be set in production |

Defaults are starting points, tuned after the first evaluation run. Exceeding a budget ends the run with
`status=budget_exhausted`.

## 4. Output: finding

See the JSON shape in [API.md](API.md). A finding includes the kind (`outlier`, `trend`, `contributor`, `driver`), the
metric and time window, the top contributing dimension, severity and confidence, a natural-language summary, the SQL
that supports it, and suggested follow-up questions. The kinds follow the insight types used by commercial BI
assistants such as Tableau Pulse.

## 5. Safety

| Threat | Control |
|---|---|
| Model writes or drops data | ClickHouse user `agent_reader` has `readonly=1`. A test asserts failure. |
| Runaway queries | `max_execution_time`, `max_rows_to_read`, `max_result_rows`, and a quota on the user. |
| Reading bronze or other tables | Grants only on silver `events` and the gold database. |
| Prompt injection through event text | Event content is quoted as data. No tools can act outside ClickHouse reads. A hostile-payload test exists. |
| Cost abuse on the public demo | Detector-triggered runs, per-run budget, per-token rate limit, daily spend cap. |
| Leaking credentials | The model never sees database credentials. Keys are read from the environment by the process. |
| Unreviewable behavior | Every tool call and its SQL is logged and stored with the finding. |

## 6. Model access

All model calls go through LiteLLM in `bromo_agent/llm/`. Switch provider with environment variables:

```bash
LLM_MODEL=deepseek/deepseek-chat   # or a GPT model name, or gemini/<model>
DEEPSEEK_API_KEY=...               # key variable follows LiteLLM's provider convention
```

Tool-calling quality differs between models. The evaluation harness runs the same suite per model and publishes recall
and cost side by side, so the default model is chosen from data.

## 7. Evaluation

The synthetic generator injects anomalies and writes `manifest.json` (start, end, dimension, type). The harness compares
findings with the manifest.

| Metric | Definition |
|---|---|
| Detection recall | Injected anomalies that have a finding in their window |
| Detection precision | Findings that match an injected anomaly |
| Time to finding | Seconds from anomaly start to finding stored |
| Explanation hit | The finding names the injected dimension and value |
| Cost per finding | Tokens and USD per finding |

A second check on labeled fraud data compares flagged windows with minutes that have a high fraud rate. A small golden
set of question and answer pairs scores the chat, following the evaluation approach in Uber's QueryGPT write-up.

Results go to `benchmarks/agent-eval.md`, including poor results with analysis. A prompt or model change requires a new
run.

## 8. gRPC contract

The internal `AgentService.Chat` stream is defined in [API.md](API.md#11-internal-grpc). Findings are not returned over
gRPC. The agent writes them to ClickHouse and `bromo-app` reads them.
