# 🧪 Spec2Test_local

> **게임 기획서를 QA 테스트케이스로 — 100% 로컬에서. API 키도, 토큰 비용도, 서비스 장애도 없이.**

[![Cost](https://img.shields.io/badge/API%20cost-%240.00-brightgreen)]()
[![Python](https://img.shields.io/badge/Python-3.10%2B-3776AB?logo=python&logoColor=white)](https://www.python.org/)
[![Ollama](https://img.shields.io/badge/LLM-Ollama%20(local)-black?logo=ollama)](https://ollama.com)
[![Flask](https://img.shields.io/badge/Web%20UI-Flask-000000?logo=flask)](https://flask.palletsprojects.com/)
[![Offline](https://img.shields.io/badge/Works-Fully%20Offline-success)]()


**[🇺🇸 English README](README.md)**

Spec2Test_local은 게임 기획서를 읽고, 로컬 비전 모델로 모든 페이지를 이해한 뒤, 팀 스타일에 맞는 **테스트케이스 CSV**와 기획자에게 전달할 **의문점 목록**을 생성합니다 — 오직 내 컴퓨터만으로.

---

## 💡 왜 만들었나 (Why this project?)

### 1. 남의 API가 죽었다고 내 작업이 멈추면 안 되니까

자동화가 OpenAI나 Claude API에 의존하면, **그쪽의 장애가 곧 내 쪽의 블로커**가 됩니다. "가동률 99.5%"라는 말은 뒤집으면 매달 몇 시간씩은 막힌다는 뜻이고, 그 몇 시간은 꼭 제일 바쁠 때 찾아옵니다.

| OpenAI 상태 페이지 (3개월) | Anthropic 상태 페이지 (90일) |
|---|---|
| ![OpenAI 상태 페이지 — 반복되는 장애 이력](image/claude%20status.png) | ![Claude 상태 페이지 — 반복되는 장애 이력](image/openAI%20status.png) |

위의 노란색·빨간색 막대 하나하나가 API 의존 파이프라인이 멈추는 구간입니다. Spec2Test_local에는 그런 구간이 **없습니다.** 내 PC가 켜져 있으면, 돌아갑니다.

### 2. 토큰 비용은 생각보다 빨리 쌓이니까

35페이지 슬라이드 기획서 하나를 분석하려면 — 페이지마다 비전 캡션, 섹션마다 TC 생성, 검증 실패 시 재시도, 병합 리뷰까지 — 문서 하나에 수백 번의 LLM 호출이 필요합니다. 종량제 API라면 기획서가 수정될 때마다 재생성 비용이 반복해서 나갑니다. Ollama에서는 100번째 실행도 첫 실행과 똑같이 **0원**입니다.

### 3. 회사 밖으로 나가면 안 되는 문서가 있으니까

기획서는 태생적으로 기밀 문서입니다. NDA 하에 일하거나, 사내망 환경이거나, 출시 전 콘텐츠를 외부 서버에 올리고 싶지 않다면 — 로컬 파이프라인은 취향이 아니라 **필수 조건**입니다.

**이런 분들을 위해 만들었습니다:** 1인·인디 개발자, 소규모 QA 팀, API 비용 없이 AI 자동화를 도입하고 싶은 분, 보안상 외부 LLM을 쓸 수 없는 환경의 작업자.

---

## ✨ 주요 기능

- 🖼️ **슬라이드형 기획서 이해** — PDF 페이지를 렌더링해 로컬 비전 모델(`qwen2.5vl`)로 캡션을 생성합니다. UI 목업·표·아트 스펙이 사각지대가 아니라 테스트 가능한 텍스트가 됩니다.
- 📋 **팀 스타일에 맞는 산출물** — *기존에 쓰던* TC CSV에서 약어·문장 패턴·분류 체계를 학습해, 생성된 TC가 팀 포맷에 그대로 녹아듭니다.
- ❓ **애매모호함 탐지** — 기획서에 명시되지 않은 내용은 지어내지 않고, 출처(페이지)가 달린 의문점 목록(`의문점_*.md`)으로 분리해 기획자에게 되돌려줍니다.
- ✅ **자가 검증 루프** — 섹션마다 규칙 기반 검증기가 CSV를 검사하고, 실패하면 오류 내용을 모델에 피드백해 최대 3회 재시도합니다.
- 🌐 **실시간 로그가 나오는 웹 UI** — PDF를 업로드하면 단계별 진행률(`[Phase 1] (3/6, 50%) ...`)이 실시간으로 스트리밍되고, 결과를 필터링 가능한 테이블로 보고 CSV/MD로 다운로드할 수 있습니다.
- 🔁 **파일 기반 이어하기** — 모든 단계가 파일로 체크포인트됩니다. 언제 꺼도, 다시 실행하면 하던 곳부터 이어서 진행합니다.
- 📦 **자동 아카이빙** — 분석이 완료될 때마다 `archive/<기획서명>_<타임스탬프>/`에 전체 산출물이 스냅샷으로 보존됩니다.
- 🛡️ **장애 내성 설계** — 일시적인 Ollama 타임아웃은 호출 단위·섹션 단위로 재시도됩니다. 한 번의 딸꾹질이 전체 실행을 죽이지 않습니다.

---

## ⚙️ 동작 방식

```
input/기획서.pdf ──► Phase 0: 페이지 렌더링 ─► 비전 캡션 ─► 섹션 인벤토리 + 스타일 가이드
                          │
                          ▼
                   Phase 1: 섹션별 — TC·의문점 생성 ─► 검증(최대 3회 재시도) ─► 체크
                          │
                          ▼
                   Phase 2: CSV 병합 ─► 의문점 병합 ─► 최종 검증 + 커버리지 리포트 ─► DONE
                          │
                          ▼
        output/TC_*.csv + output/의문점_*.md  (+ archive/ 스냅샷)
```

제어 흐름은 순수 파이썬(`scripts/local_pipeline.py`)이 담당하고, LLM은 범위가 명확한 생성 작업에만 호출됩니다. 의도된 설계입니다 — 30B급 로컬 모델은 훌륭한 생성기지만 장시간 자기주도 판단은 불안정하므로, 파이프라인은 모델에게 판단을 맡기지 않습니다.

아키텍처 다이어그램은 [`docs/`](docs/)에 있습니다.

---

## 🚀 빠른 시작

### 사전 준비

1. **[Ollama](https://ollama.com)** 설치 후 실행 중인지 확인 (`ollama serve`, Windows는 보통 트레이 앱이 자동 실행).
2. 모델 2개를 pull합니다 (교체 가능 — [설정](#-설정) 참고):
   ```bash
   ollama pull qwen3-coder:30b    # 텍스트: 섹션 인벤토리, TC 생성, 병합 리뷰
   ollama pull qwen2.5vl:32b      # 비전: 슬라이드 캡션
   ```
   > 💻 VRAM 24GB 정도면 쾌적합니다. 사양이 낮다면 더 가벼운 모델로 교체하세요 — 상수 한 줄만 바꾸면 됩니다.
3. 파이썬 패키지 설치:
   ```bash
   pip install flask pymupdf
   ```

### 실행 (웹 UI — 권장)

```bash
python app.py
```

브라우저에서 **http://localhost:5000** 접속 후:
- 기획서 **PDF** (필수)
- 스타일 가이드로 쓸 기존 **TC CSV** (한 번 올리면 유지됨)

를 올리고 **분석 시작**을 누르면 됩니다. 실시간 로그를 지켜보다가, 완료되면 결과를 다운로드하세요.

### 실행 (CLI)

```bash
python scripts/local_pipeline.py          # 남은 작업 전부 처리 (이어하기 지원)
# 또는 한 번에 섹션 하나씩 루프:
./loop.sh          # bash
.\loop.ps1         # PowerShell
```

---

## 📄 산출물

| 파일 | 내용 |
|---|---|
| `output/TC_<기획서명>.csv` | 병합·번호 재부여·검증 통과된 테스트케이스 (No / 대·중·소분류 / 테스트 항목 / 사전조건 / 테스트 스텝 / 기대결과 / 비고) |
| `output/의문점_<기획서명>.md` | 중복 제거된 기획서 의문점 목록 (각 항목에 페이지 출처 표기) |
| `state/coverage_report.md` | RULES 체크리스트 기반 LLM 자가 감사 — 어디가 커버됐고 어디를 사람이 봐야 하는지 |
| `archive/<기획서명>_<타임스탬프>/` | 위 산출물 + 중간 산출물 전체 스냅샷 (실행마다 보존) |

---

## 🗂️ 프로젝트 구조

```
Spec2Test_local/
├── app.py                     # Flask 웹 UI (업로드, 실시간 로그, 결과 테이블)
├── templates/index.html       # 웹 UI 프론트엔드
├── scripts/local_pipeline.py  # 파이프라인 엔진 (Ollama 호출, 단계, 재시도)
├── scripts/validate_csv.py    # 규칙 기반 CSV 검증기
├── PROMPT.md / RULES.md       # 절차·TC 작성 규칙 (단일 근거 문서)
├── loop.ps1 / loop.sh         # CLI 루프 러너
├── docs/                      # 아키텍처 다이어그램 (UML, 시퀀스, 유즈케이스)
├── image/                     # README 이미지
├── input/                     # ← 기획서 PDF + 스타일 가이드 CSV (gitignore)
├── state/ work/ output/       # 런타임 상태·산출물 (자동 관리, gitignore)
└── archive/                   # 실행별 스냅샷 (gitignore)
```

---

## 🔧 설정

모든 설정은 `scripts/local_pipeline.py` 상단의 상수입니다:

| 상수 | 기본값 | 의미 |
|---|---|---|
| `MODEL_TEXT` | `qwen3-coder:30b` | 섹션 분할, TC/의문점 생성, 병합 리뷰 |
| `MODEL_VISION` | `qwen2.5vl:32b` | 페이지별 슬라이드 캡션 |
| `OLLAMA_HOST` | `http://localhost:11434` | Ollama 엔드포인트 |
| `NUM_CTX` | `65536` | 호출당 컨텍스트 길이 |
| `MAX_VALIDATE_RETRIES` | `3` | 섹션당 생성 재시도 횟수 |
| `PAGE_RENDER_DPI` | `150` | PDF 렌더링 해상도 |

---

## ⚠️ 개선해야할 한계

- 30B급 로컬 모델은 장문 판단·미묘한 애매모호함 분류에서 프론티어 API 대비 정확도가 떨어집니다 — 산출물은 **사람 검수를 전제**로 설계되었고, `state/coverage_report.md`가 어디부터 봐야 할지 알려줍니다.
- 비전 캡션 품질이 아트/UI TC 정확도를 좌우합니다. `work/spec/pages/*.vision.md`를 한 번 훑어보고, 틀린 캡션이 있으면 그 파일만 지우고 재실행하세요 — 해당 페이지만 다시 캡션됩니다.
- Ollama가 완전히 죽어 있으면 무한 대기하지 않고, 웹 UI의 `NEEDS_HUMAN` 배너에 명확한 사유를 띄우고 멈춥니다.

---

## 📜 라이선스

TBD.

---

## 📝 Changelog

이 프로젝트는 [Keep a Changelog](https://keepachangelog.com/) 형식을 따릅니다.

### [0.1.0] — Unreleased
- 파일 업로드, 실시간 SSE 로그 스트리밍, 진행 체크리스트, 필터링 가능한 결과 테이블을 갖춘 웹 UI (`app.py`, `templates/index.html`) 추가.
- 일시적인 Ollama 오류에 대한 호출 단위·섹션 단위 재시도 — 타임아웃 한 번으로 전체 실행이 죽지 않도록 함.
- 단계별·섹션별 진행률(%)과 타임스탬프가 포함된 상세 파이프라인 로깅.
- `archive/<기획서명>_<타임스탬프>/`로의 실행별 자동 아카이빙.
- Windows 서브프로세스 관련 버그 수정 (콘솔 상속으로 인한 즉사 문제, SSE 이벤트 프레이밍 버그).
