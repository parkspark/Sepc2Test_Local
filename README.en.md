# 🧪 Spec2Test_local

> **Turn game design docs into QA test cases — 100% locally. No API keys. No token bills. No downtime.**

[![Cost](https://img.shields.io/badge/API%20cost-%240.00-brightgreen)]()
[![Python](https://img.shields.io/badge/Python-3.10%2B-3776AB?logo=python&logoColor=white)](https://www.python.org/)
[![Ollama](https://img.shields.io/badge/LLM-Ollama%20(local)-black?logo=ollama)](https://ollama.com)
[![Flask](https://img.shields.io/badge/Web%20UI-Flask-000000?logo=flask)](https://flask.palletsprojects.com/)
[![Offline](https://img.shields.io/badge/Works-Fully%20Offline-success)]()


**[🇰🇷 한국어 README](README.md)**

Spec2Test_local reads a game design document, understands every page with a local vision model, and generates a complete, style-consistent **test case CSV** plus a **list of spec ambiguities** for your designers — using nothing but your own machine.

---

## 💡 Why this project?

### 1. Your workflow shouldn't go down because someone else's API did

If your automation depends on OpenAI or Claude APIs, every incident on *their* side becomes a blocker on *your* side. Even a "99.5% uptime" service means hours of degraded or blocked work every month — usually at the worst possible moment.

| OpenAI status (3 months) | Anthropic status (90 days) |
|---|---|
| ![OpenAI status page showing recurring incidents](image/openai%20status.png) | ![Claude status page showing recurring incidents](image/claude%20status.png) |

Every yellow and red bar above is a window where an API-dependent pipeline stalls. Spec2Test_local has **zero** of those windows: if your PC is on, it works.

### 2. Token bills add up fast

Analyzing a 35-page slide deck means vision-captioning every page, generating test cases section by section, validating, retrying, and merging — easily hundreds of LLM calls per document. On a metered API, iterating on that (new spec revision → regenerate) costs real money every single run. On Ollama, the marginal cost of run #100 is the same as run #1: **$0**.

### 3. Some specs must never leave the building

Game design documents are confidential by nature. If you work under NDA, on an internal network, or just don't want pre-release content uploaded to a third party, a local pipeline isn't a preference — it's a requirement.

**Who this is for:** solo & indie developers, small QA teams, anyone who wants LLM automation without API costs, and teams in security-restricted environments.

---

## ✨ Key Features

- 🖼️ **Understands slide-based specs** — renders each PDF page and captions it with a local vision model (`qwen2.5vl`), so UI mockups, tables, and art references become testable text, not blind spots.
- 📋 **Style-consistent output** — learns abbreviations, sentence patterns, and category structure from *your existing* test case CSV, so generated TCs match your team's format.
- ❓ **Ambiguity detection** — anything underspecified in the doc becomes a sourced question list (`의문점_*.md`) for your designers instead of a hallucinated test case.
- ✅ **Self-validating loop** — every section's CSV is checked by a rule-based validator; failures are fed back to the model for up to 3 retries before a section is marked blocked.
- 🌐 **Web UI with live logs** — upload a PDF, watch phase-by-phase progress (`[Phase 1] (3/6, 50%) ...`) stream in real time, browse results in a filterable table, download CSV/MD.
- 🔁 **Resumable, file-based state** — every step is checkpointed to plain files. Kill it anytime; rerun and it continues where it left off.
- 📦 **Automatic archiving** — each completed run is snapshotted to `archive/<spec-name>_<timestamp>/` before the next run clears the workspace.
- 🛡️ **Fault-tolerant by design** — transient Ollama timeouts are retried per-call and per-section; one hiccup never kills a whole run.

---

## ⚙️ How it works

```
input/spec.pdf ──► Phase 0: render pages ─► vision captions ─► section inventory + style guide
                          │
                          ▼
                   Phase 1: per section — generate TCs + questions ─► validate (≤3 retries) ─► check off
                          │
                          ▼
                   Phase 2: merge CSVs ─► merge questions ─► final validation + coverage report ─► DONE
                          │
                          ▼
        output/TC_*.csv + output/의문점_*.md  (+ archive/ snapshot)
```

The control flow lives in plain Python (`scripts/local_pipeline.py`) — the LLM is only invoked for well-scoped generation tasks. This is a deliberate design choice: 30B-class local models are excellent generators but unreliable long-horizon planners, so the pipeline never asks them to be one.

Architecture diagrams live in [`docs/`](docs/).

---

## 🚀 Quick Start

### Prerequisites

1. **[Ollama](https://ollama.com)** installed and running (`ollama serve`, or the tray app on Windows).
2. Pull the two models (swappable — see [Configuration](#-configuration)):
   ```bash
   ollama pull qwen3-coder:30b    # text: inventory, TC generation, review
   ollama pull qwen2.5vl:32b      # vision: slide captioning
   ```
   > 💻 Comfortable with ~24 GB VRAM. Smaller machines: swap in lighter models — it's a one-line change.
3. Python packages:
   ```bash
   pip install flask pymupdf
   ```

### Run (Web UI — recommended)

```bash
python app.py
```

Open **http://localhost:5000**, drop in:
- your design doc **PDF** (required)
- an existing **TC CSV** to use as the style guide (kept between runs)

…and hit **Start Analysis (분석 시작)**. Watch the live log, then download your results.

### Run (CLI)

```bash
python scripts/local_pipeline.py          # process everything, resumable
# or step one section at a time in a loop:
./loop.sh          # bash
.\loop.ps1         # PowerShell
```

---

## 📄 What you get

| File | What it is |
|---|---|
| `output/TC_<spec>.csv` | Merged, renumbered, validation-passing test cases (No / 3-level category / item / precondition / steps / expected result / note) |
| `output/의문점_<spec>.md` | Deduplicated list of spec ambiguities, each with a page source |
| `state/coverage_report.md` | LLM self-audit against the RULES checklist — what's covered, what needs human review |
| `archive/<spec>_<timestamp>/` | Full snapshot of the above + intermediate artifacts, kept per run |

---

## 🗂️ Project structure

```
Spec2Test_local/
├── app.py                     # Flask web UI (upload, live logs, results table)
├── templates/index.html       # Web UI frontend
├── scripts/local_pipeline.py  # Pipeline engine (Ollama calls, phases, retries)
├── scripts/validate_csv.py    # Rule-based CSV validator
├── PROMPT.md / RULES.md       # Procedure & TC writing rules (single source of truth)
├── loop.ps1 / loop.sh         # CLI loop runners
├── docs/                      # Architecture diagrams (UML, sequence, use-case)
├── image/                     # README assets
├── input/                     # ← your PDF + style-guide CSV (gitignored)
├── state/ work/ output/       # Runtime state & artifacts (auto-managed, gitignored)
└── archive/                   # Per-run snapshots (gitignored)
```

---

## 🔧 Configuration

All knobs are constants at the top of `scripts/local_pipeline.py`:

| Constant | Default | Meaning |
|---|---|---|
| `MODEL_TEXT` | `qwen3-coder:30b` | Sectioning, TC/question generation, merge review |
| `MODEL_VISION` | `qwen2.5vl:32b` | Per-page slide captioning |
| `OLLAMA_HOST` | `http://localhost:11434` | Ollama endpoint |
| `NUM_CTX` | `65536` | Context window per call |
| `MAX_VALIDATE_RETRIES` | `3` | Generation retries per section |
| `PAGE_RENDER_DPI` | `150` | PDF render resolution |

---

## ⚠️ Known limitations

- 30B-class local models trail frontier APIs on long-document judgment and subtle ambiguity classification — the output is designed for **human review**, and `state/coverage_report.md` tells you where to look first.
- Vision caption quality drives art/UI TC accuracy. Skim `work/spec/pages/*.vision.md`; delete any bad caption and rerun — only that page is re-captioned.
- If Ollama is completely down, the run stops with a clear reason in the UI (`NEEDS_HUMAN` banner) rather than hanging forever.

---

## 📜 License

TBD.

---

## 📝 Changelog

This project follows [Keep a Changelog](https://keepachangelog.com/) conventions.

### [0.1.0] — Unreleased
- Web UI (`app.py`, `templates/index.html`) with file upload, live SSE log streaming, progress checklist, and a filterable results table.
- Per-section and per-call retry for transient Ollama errors, so one timeout no longer aborts the whole run.
- Detailed, timestamped pipeline logging with per-phase and per-section progress percentages.
- Automatic per-run archiving to `archive/<spec-name>_<timestamp>/`.
- Windows subprocess fixes (console-inheritance crash, SSE event-framing bug).
