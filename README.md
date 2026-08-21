# 🧪 Spec2Test

> **게임 기획서를 QA 테스트케이스로 — 로컬 LLM 기반, Spring Boot + PostgreSQL + Docker로 컨테이너화.**

[![Java](https://img.shields.io/badge/Java-17-ED8B00?logo=openjdk&logoColor=white)]()
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1-6DB33F?logo=spring&logoColor=white)]()
[![Spring AI](https://img.shields.io/badge/Spring%20AI-2.0-6DB33F?logo=spring&logoColor=white)]()
[![React](https://img.shields.io/badge/React-19-61DAFB?logo=react&logoColor=white)]()
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-4169E1?logo=postgresql&logoColor=white)]()
[![Ollama](https://img.shields.io/badge/LLM-Ollama%20(local)-black?logo=ollama)](https://ollama.com)
[![Docker](https://img.shields.io/badge/Docker-Compose-2496ED?logo=docker&logoColor=white)]()

**[🇺🇸 English README](README.en.md)**

Spec2Test는 게임 기획서 PDF를 읽고, 로컬 비전 모델로 모든 페이지를 이해한 뒤, 팀 스타일에 맞는 **테스트케이스 CSV**와 기획자에게 전달할 **의문점 목록**을 생성합니다. 기본 LLM은 API 키·토큰 비용 없이 완전히 로컬에서 도는 Ollama이며, 설정만으로 OpenAI/Anthropic으로 전환할 수 있습니다.

### 🎬 시연 영상

시연영상 : https://youtu.be/bJy3O2Tp34M
![alt text](image/Demo.gif)

> 데모 영상은 이전 Python/Flask 버전 기준이며, 핵심 파이프라인 절차(Phase 0/1/2)와 산출물 형식은 동일합니다.

---

## 💡 왜 로컬 LLM인가

- **API 장애가 내 작업을 막지 않는다.** 외부 LLM API에 의존하면 그쪽 장애가 곧 내 블로커가 됩니다. 내 PC(또는 사내 GPU 서버)가 켜져 있으면 돌아갑니다.
- **토큰 비용이 반복되지 않는다.** 기획서 하나를 분석하려면 수백 번의 LLM 호출이 필요합니다 — 종량제라면 재생성마다 비용이 나가지만, Ollama에서는 100번째 실행도 0원입니다.
- **기획서가 회사 밖으로 나가지 않는다.** NDA·사내망·출시 전 콘텐츠 보안이 걸린 환경에서는 로컬 처리가 필수 조건입니다.

---

## ✨ 주요 기능

- 🖼️ **슬라이드형 기획서 이해** — PDF 페이지를 렌더링해 비전 모델(`qwen2.5vl`)로 캡션을 생성합니다.
- 📋 **팀 스타일에 맞는 산출물** — 참고 TC CSV에서 약어·문장 패턴·분류 체계를 학습합니다.
- 🔎 **참고 TC RAG** — 참고 CSV 전체를 행 단위로 인덱싱하고, 기획 섹션마다 유사한 TC만 검색해 테스트 관점을 보강합니다. 기획서에 없는 내용은 참고 사례에서 복사하지 않습니다.
- ❓ **애매모호함 탐지** — 명시되지 않은 내용은 지어내지 않고 출처(페이지) 있는 의문점 목록으로 분리합니다.
- ✅ **자가 검증 루프** — 섹션마다 규칙 기반 검증기가 CSV를 검사하고, 실패하면 오류를 모델에 피드백해 재시도합니다.
- 🌐 **실시간 로그가 나오는 웹 UI** — React 프론트엔드가 진행률을 SSE로 실시간 스트리밍하고, 결과를 필터링 가능한 테이블로 보여줍니다.
- 🗄️ **PostgreSQL 영속화** — 업로드 원본, 페이지별 캡션, 섹션, 생성된 TC/의문점, 커버리지 리포트, 실행 로그까지 전부 DB에 저장됩니다. 실행 이력이 자동으로 보존됩니다.
- 🔁 **DB 상태 기반 이어하기** — 각 단계가 이미 완료된 작업을 스스로 건너뛰므로, 중단(Stop) 후 재개(Resume)하거나 프로세스가 죽었다 재시작해도 하던 곳부터 이어집니다.
- 🔌 **LLM 프로바이더 전환 가능** — 기본은 Ollama, 설정 하나로 OpenAI/Anthropic으로 전환 가능한 구조입니다.

---

## ⚙️ 동작 방식

```
PDF 업로드 ──► Phase 0: 페이지 렌더링 ─► 비전 캡션 ─► 섹션 인벤토리 + 스타일 가이드
                  │
                  ▼
           Phase 1: 섹션별 — TC·의문점 생성 ─► 검증(최대 3회 재시도)
                  │
                  ▼
           Phase 2: CSV 병합 ─► 의문점 병합 ─► 최종 검증 + 커버리지 리포트 ─► DONE
                  │
                  ▼
     PostgreSQL에 전부 저장 (test_case / question / document 테이블 등)
     → React UI에서 CSV/MD 다운로드
```

제어 흐름은 Spring Boot 백엔드(`PipelineService` + `pipeline/steps/*`)가 담당하고, LLM(Spring AI `ChatModel`)은 범위가 명확한 생성 작업에만 호출됩니다. 30B급 로컬 모델은 훌륭한 생성기지만 장시간 자기주도 판단은 불안정하므로, 파이프라인이 모델에게 판단을 맡기지 않는 설계는 이전 버전과 동일합니다.

아키텍처 다이어그램은 [`docs/`](docs/)에 있습니다.

---

## 🚀 빠른 시작 (Docker)

### 사전 준비

1. **[Ollama](https://ollama.com)** 를 호스트에 설치하고 실행 (`ollama serve`).
2. 모델 3개를 pull:
   ```bash
   ollama pull qwen3-coder:30b    # 텍스트: 섹션 인벤토리, TC 생성, 병합 리뷰
   ollama pull qwen2.5vl:32b      # 비전: 슬라이드 캡션
   ollama pull nomic-embed-text   # 참고 TC RAG 의미 검색
   ```
3. **Docker Desktop** 설치.

### 실행

```bash
cp .env.example .env
docker compose up -d --build
```

브라우저에서 **http://localhost:8080** 접속 후 기획서 PDF(필수)와 참고 TC CSV(선택, 생략 시 이전 참고 CSV 재사용)를 올리고 **분석 시작**을 누르면 됩니다. Ollama는 컨테이너 밖 호스트에서 실행되며, 앱 컨테이너는 `host.docker.internal:11434`로 접속합니다.

### 로컬 개발 (컨테이너 없이)

```bash
docker compose up -d db                     # PostgreSQL만 컨테이너로
cd backend && ./mvnw spring-boot:run         # 백엔드 (8080)
cd frontend && npm install && npm run dev    # 프론트엔드 (5173, /api는 8080으로 프록시)
```

---

## 📄 산출물 (DB에 저장, UI에서 다운로드)

| 테이블/문서 | 내용 |
|---|---|
| `test_case` | 병합·전역 번호 재부여·검증 통과된 테스트케이스 (다운로드 시 `TC_<기획서명>.csv`, UTF-8 BOM) |
| `document(MERGED_QUESTIONS)` | 중복 제거된 기획서 의문점 목록 (다운로드 시 `의문점_<기획서명>.md`) |
| `document(COVERAGE_REPORT)` | RULES 체크리스트 기반 LLM 자가 감사 |
| `page.vision_caption` | 페이지별 비전 캡션 원문 — 아트/UI TC 정확도 검수용 |
| `log_line` | 실행 로그 전문 (SSE로 실시간 스트리밍, 새로고침해도 이어보기 가능) |

---

## 🗂️ 프로젝트 구조

```
Spec2Test_local/
├── backend/                    # Spring Boot (Java 17, Spring AI, PostgreSQL/JPA/Flyway)
│   └── src/main/
│       ├── java/com/spec2test/
│       │   ├── api/            # REST 컨트롤러 (upload/status/stop/resume/logs/outputs)
│       │   ├── domain/ repo/   # JPA 엔티티·리포지토리
│       │   ├── pipeline/       # PipelineService(오케스트레이터) + steps/*
│       │   ├── llm/            # Spring AI ChatModel 래퍼(LlmGateway), 프롬프트 로더
│       │   ├── csv/            # TC 검증기·CSV 라이터
│       │   └── logging/        # DB 기반 로그 + SSE 팬아웃
│       └── resources/
│           ├── db/migration/   # Flyway 스키마
│           └── prompts/        # 프롬프트 템플릿 + RULES.md 런타임 사본
├── frontend/                    # React + Vite + TypeScript
├── Dockerfile                   # 멀티스테이지 빌드 (node → maven → JRE)
├── docker-compose.yml           # app + postgres (Ollama는 호스트)
├── PROMPT.md / RULES.md         # 절차·TC 작성 규칙 (단일 근거 문서, docs/prompts에도 이식됨)
├── docs/                        # 아키텍처 다이어그램
└── image/                       # README 이미지
```

---

## 🔧 설정

`docker-compose.yml` / `.env` 환경변수로 제어합니다 (`.env.example` 참고):

| 변수 | 기본값 | 의미 |
|---|---|---|
| `SPEC2TEST_LLM_PROVIDER` | `ollama` | `ollama` \| `openai` \| `anthropic` |
| `SPEC2TEST_TEXT_MODEL` | `qwen3-coder:30b` | 섹션 분할, TC/의문점 생성, 병합 리뷰 |
| `SPEC2TEST_VISION_MODEL` | `qwen2.5vl:32b` | 페이지별 슬라이드 캡션 |
| `SPEC2TEST_EMBEDDING_MODEL` | `nomic-embed-text` | RAG 의미 검색용 Ollama 임베딩 모델 (`ollama pull nomic-embed-text`) |
| `SPEC2TEST_EMBEDDING_PROVIDER` | `ollama` | RAG 임베딩 제공자. 채팅 제공자와 독립적으로 설정 가능 |
| `SPEC2TEST_RAG_TOP_K` | `6` | 섹션별 프롬프트에 주입할 유사 참고 TC 수 |
| `OLLAMA_BASE_URL` | `http://host.docker.internal:11434` | Ollama 엔드포인트 |
| `OPENAI_API_KEY` / `ANTHROPIC_API_KEY` | (빈 값) | 클라우드 프로바이더 사용 시에만 필요 |

그 외 컨텍스트 길이·타임아웃·재시도 횟수 등은 `backend/src/main/resources/application.yml`의 `spec2test.llm.*`에서 조정합니다.

---

## ⚠️ 개선해야할 한계

- 30B급 로컬 모델은 장문 판단·미묘한 애매모호함 분류에서 프론티어 API 대비 정확도가 떨어집니다 — 산출물은 **사람 검수를 전제**로 설계되었고, 커버리지 리포트가 어디부터 봐야 할지 알려줍니다.
- 비전 캡션 품질이 아트/UI TC 정확도를 좌우합니다. `page.vision_caption`을 검수하세요.
- Stop은 협조적 취소이며 진행 중인 LLM 호출 하나가 끝날 때까지 지연될 수 있습니다.
