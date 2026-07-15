#!/usr/bin/env python3
"""로컬 LLM(Ollama) 기반 TC 생성 파이프라인.

Claude Code 무인 루프(loop.ps1 + PROMPT.md) 대신, Ollama에서 도는 로컬 모델로
같은 절차(PROMPT.md Phase 0/1/2, RULES.md 규칙)를 수행한다.
차이점: 여기서는 파이썬이 제어 흐름(어느 섹션을 처리할지, 언제 재시도할지,
언제 병합할지)을 직접 결정하고, LLM은 범위가 명확한 생성 작업에만 쓰인다.
오픈웨이트 30B급 모델은 Claude Code처럼 장시간 자기주도 판단이 안정적이지
않기 때문에 이렇게 설계했다.

사용법:
  python scripts/local_pipeline.py            # 남은 섹션을 모두 이어서 처리
  python scripts/local_pipeline.py --once      # 한 반복(섹션 하나 등)만 처리하고 종료

사전 준비:
  pip install pymupdf
  ollama pull qwen2.5vl:32b   (qwen3-coder:30b는 이미 있다고 가정)
  ollama serve  (백그라운드 실행 확인)
"""
import base64
import csv
import io
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

# loop.ps1을 거치지 않고 이 스크립트를 직접 실행할 때도(콘솔 코드페이지가 UTF-8이
# 아닐 수 있음) 한글 print()가 깨지지 않도록 표준출력 인코딩을 명시적으로 고정한다.
for _stream in (sys.stdout, sys.stderr):
    if hasattr(_stream, "reconfigure"):
        _stream.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parent.parent
INPUT_DIR = ROOT / "input"
STATE_DIR = ROOT / "state"
WORK_DIR = ROOT / "work"
OUTPUT_DIR = ROOT / "output"
ARCHIVE_DIR = ROOT / "archive"
SPEC_PAGES_DIR = WORK_DIR / "spec" / "pages"
TC_DIR = WORK_DIR / "tc"
QUESTIONS_DIR = WORK_DIR / "questions"

# ── 모델 설정 (필요하면 여기만 바꾸면 됨) ──────────────────────────────
OLLAMA_HOST = "http://localhost:11434"
MODEL_TEXT = "qwen3-coder:30b"     # 섹션 인벤토리 / TC·의문점 생성 / 병합 리뷰
MODEL_VISION = "qwen2.5vl:32b"     # 슬라이드 이미지 캡션
NUM_CTX = 65536  # 32768로는 페이지 텍스트+비전캡션을 포함한 섹션 컨텍스트가 조용히 잘릴 수 있었음
MAX_VALIDATE_RETRIES = 3
PAGE_RENDER_DPI = 150

_RUN_START = time.time()


def log(msg):
    """웹 UI 콘솔(run.log)에 시각·경과시간과 함께 진행 상황을 남긴다."""
    elapsed = time.time() - _RUN_START
    h, rem = divmod(int(elapsed), 3600)
    m, s = divmod(rem, 60)
    print(f"[{time.strftime('%H:%M:%S')} +{h:02d}:{m:02d}:{s:02d}] {msg}")


CSV_HEADER = ["No", "대분류", "중분류", "소분류", "테스트 항목",
              "사전조건", "테스트 스텝", "기대결과", "비고"]
TC_FIELDS = ["대분류", "중분류", "소분류", "테스트 항목",
             "사전조건", "테스트 스텝", "기대결과", "비고"]


# ── Ollama 호출 ────────────────────────────────────────────────────────
class OllamaError(RuntimeError):
    pass


def ollama_chat(model, messages, images=None, json_schema=None,
                 num_ctx=NUM_CTX, temperature=0.2):
    """POST /api/chat, 응답 message.content 문자열을 반환한다."""
    if images:
        messages = list(messages)
        messages[-1] = dict(messages[-1], images=images)

    payload = {
        "model": model,
        "messages": messages,
        "stream": False,
        "options": {"num_ctx": num_ctx, "temperature": temperature},
    }
    if json_schema is not None:
        payload["format"] = json_schema

    body = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        f"{OLLAMA_HOST}/api/chat", data=body,
        headers={"Content-Type": "application/json"}, method="POST",
    )
    try:
        # 로컬 GPU 추론은 컨텍스트 길이·출력 길이에 따라 소요 시간 편차가 크므로
        # (실제로 600초 타임아웃에 걸린 적이 있다) 넉넉하게 잡는다.
        with urllib.request.urlopen(req, timeout=1800) as resp:
            data = json.loads(resp.read().decode("utf-8"))
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        raise OllamaError(
            f"Ollama({OLLAMA_HOST})에 연결할 수 없다: {e}\n"
            f"'ollama serve'가 실행 중인지, 모델 '{model}'이 pull되어 있는지 확인하라."
        ) from e
    if "message" not in data:
        raise OllamaError(f"Ollama 응답 형식이 예상과 다르다: {data}")
    return data["message"]["content"]


def ollama_chat_json(model, messages, json_schema, images=None, **kw):
    """구조화 출력을 강제하고 파싱된 dict/list를 반환한다. 실패 시 1회 재시도."""
    last_err = None
    for attempt in range(2):
        content = ollama_chat(model, messages, images=images,
                               json_schema=json_schema, **kw)
        try:
            return json.loads(content)
        except json.JSONDecodeError as e:
            last_err = e
            messages = list(messages) + [
                {"role": "assistant", "content": content},
                {"role": "user", "content": "출력이 올바른 JSON이 아니다. JSON만 다시 출력하라."},
            ]
    raise OllamaError(f"모델이 유효한 JSON을 반환하지 않았다: {last_err}")


def with_ollama_retries(fn, *args, retries=3, delay=10, **kw):
    """네트워크 단절/타임아웃 등 일시적 OllamaError는 여기서 흡수하고 재시도한다.
    (재시도 없이 그대로 올려보내면 main()의 try/except가 파이프라인 전체를 죽여버려서,
    섹션 하나·페이지 한 장 처리 중 생긴 일시 오류로 나머지 섹션 전체가 통째로 날아갔었다.)"""
    last_err = None
    for attempt in range(1, retries + 1):
        try:
            return fn(*args, **kw)
        except OllamaError as e:
            last_err = e
            print(f"  [경고] Ollama 호출 실패 (시도 {attempt}/{retries}): {e}")
            if attempt < retries:
                time.sleep(delay)
    raise last_err


# ── 유틸 ────────────────────────────────────────────────────────────────
def read_text(path, default=""):
    p = Path(path)
    return p.read_text(encoding="utf-8") if p.exists() else default


def write_text(path, content):
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text(content, encoding="utf-8")


def append_notes(line):
    ts = time.strftime("%Y-%m-%d %H:%M:%S")
    notes_path = STATE_DIR / "NOTES.md"
    existing = read_text(notes_path, "# NOTES\n")
    write_text(notes_path, existing.rstrip("\n") + f"\n- [{ts}] {line}\n")


def image_to_b64(png_path):
    return base64.b64encode(Path(png_path).read_bytes()).decode("ascii")


_INVALID_FILENAME_CHARS = re.compile(r'[\\/:*?"<>|]')


def sanitize_title(title):
    """섹션 제목을 Windows에서도 안전한 파일명 조각으로 정리한다."""
    cleaned = _INVALID_FILENAME_CHARS.sub("_", title).strip().strip(".")
    return cleaned or "섹션"


def rules_text():
    return read_text(ROOT / "RULES.md")


# ── Phase 0: 초기화 ─────────────────────────────────────────────────────
def find_input_pdf():
    pdfs = list(INPUT_DIR.glob("*.pdf"))
    if len(pdfs) != 1:
        return None
    return pdfs[0]


def find_input_tc_csv():
    csvs = list(INPUT_DIR.glob("*.csv")) + list(INPUT_DIR.glob("*.xlsx"))
    return csvs[0] if csvs else None


def needs_human(reason):
    write_text(STATE_DIR / "NEEDS_HUMAN", reason)
    log(f"[NEEDS_HUMAN] {reason}")


def render_pages(pdf_path):
    """PDF 각 페이지를 PNG로 렌더링하고 텍스트 레이어를 추출한다. 존재하면 스킵."""
    import fitz  # PyMuPDF

    doc = fitz.open(pdf_path)
    n_pages = doc.page_count
    zoom = PAGE_RENDER_DPI / 72
    mat = fitz.Matrix(zoom, zoom)
    SPEC_PAGES_DIR.mkdir(parents=True, exist_ok=True)

    for i in range(n_pages):
        idx = i + 1
        png_path = SPEC_PAGES_DIR / f"{idx:03d}.png"
        txt_path = SPEC_PAGES_DIR / f"{idx:03d}.txt"
        if png_path.exists() and txt_path.exists():
            continue
        page = doc.load_page(i)
        if not png_path.exists():
            pix = page.get_pixmap(matrix=mat)
            pix.save(str(png_path))
        if not txt_path.exists():
            write_text(txt_path, page.get_text("text"))
        pct = round(idx / n_pages * 100)
        log(f"  [render] {idx:03d}/{n_pages} ({pct}%)")
    doc.close()
    return n_pages


def caption_pages(n_pages):
    """각 페이지 이미지를 비전 모델로 캡션하고 combined.md를 만든다. 존재하면 스킵."""
    for i in range(1, n_pages + 1):
        combined_path = SPEC_PAGES_DIR / f"{i:03d}.combined.md"
        if combined_path.exists():
            continue
        png_path = SPEC_PAGES_DIR / f"{i:03d}.png"
        vision_path = SPEC_PAGES_DIR / f"{i:03d}.vision.md"
        txt = read_text(SPEC_PAGES_DIR / f"{i:03d}.txt")

        if vision_path.exists():
            vision = read_text(vision_path)
        else:
            pct = round(i / n_pages * 100)
            log(f"  [vision] {i:03d}/{n_pages} ({pct}%)")
            vision = with_ollama_retries(
                ollama_chat,
                MODEL_VISION,
                [{
                    "role": "user",
                    "content": (
                        "이것은 게임 기획서 슬라이드 한 장이다. QA가 테스트케이스를 작성할 수 있도록 "
                        "화면에 보이는 모든 요소를 빠짐없이 한국어로 서술하라: "
                        "UI 요소(버튼/팝업/게이지/텍스트 등)의 배치와 이름, 색상, 아이콘/이펙트의 "
                        "종류와 개수와 방향, 애니메이션·연출 흐름, 표(테이블)가 있으면 행/열 값을 "
                        "빠짐없이 옮겨 적어라. 슬라이드에 적힌 한글 텍스트도 읽을 수 있는 대로 그대로 옮겨라. "
                        "설명 문장으로만 답하고 다른 말은 하지 마라."
                    ),
                }],
                images=[image_to_b64(png_path)],
            )
            write_text(vision_path, vision)

        write_text(
            combined_path,
            f"## 페이지 {i:03d}\n\n### 텍스트 레이어\n{txt.strip() or '(없음)'}\n\n"
            f"### 시각 설명 (비전 모델)\n{vision.strip()}\n",
        )


SECTIONS_NUM_CTX = 131072  # 페이지 수가 많으면 combined_all이 32k 토큰을 쉽게 넘는다


def _sections_coverage_gaps(sections, n_pages):
    covered = set()
    for s in sections:
        covered.update(range(s["page_start"], s["page_end"] + 1))
    return sorted(set(range(1, n_pages + 1)) - covered)


def _autoclose_gap_pages(sections, n_pages):
    """모델이 빠뜨린 갭 페이지 중, 인접 섹션에 붙일 수 있는 것은 자동으로 편입시킨다.
    (예: 콘텐츠 없는 챕터 구분용 타이틀 슬라이드 — 다음 섹션의 시작 페이지로 흡수)
    양쪽 다 인접 섹션이 없는 고립된 갭은 그대로 남겨 호출자가 처리하게 한다."""
    sections = [dict(s) for s in sections]
    for gap in _sections_coverage_gaps(sections, n_pages):
        sections.sort(key=lambda s: s["page_start"])
        next_sec = next((s for s in sections if s["page_start"] == gap + 1), None)
        prev_sec = next((s for s in sections if s["page_end"] == gap - 1), None)
        if next_sec is not None:
            next_sec["page_start"] = gap
        elif prev_sec is not None:
            prev_sec["page_end"] = gap
        # 둘 다 없으면 이 갭은 못 닫는다 — 남은 갭으로 호출자에게 보고됨
    return sections


def build_sections_md(pdf_name, n_pages):
    out_path = STATE_DIR / "sections.md"
    if out_path.exists():
        return read_text(out_path)

    combined_all = "\n\n".join(
        read_text(SPEC_PAGES_DIR / f"{i:03d}.combined.md") for i in range(1, n_pages + 1)
    )
    schema = {
        "type": "object",
        "properties": {
            "sections": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {
                        "no": {"type": "string"},
                        "title": {"type": "string"},
                        "page_start": {"type": "integer"},
                        "page_end": {"type": "integer"},
                        "category_hint": {"type": "string"},
                    },
                    "required": ["no", "title", "page_start", "page_end", "category_hint"],
                },
            }
        },
        "required": ["sections"],
    }
    messages = [{
        "role": "user",
        "content": (
            f"다음은 게임 기획서 전체 {n_pages}페이지(1~{n_pages})의 텍스트+시각 설명이다. "
            "각 페이지는 '## 페이지 NNN'으로 시작한다. 목차·구조를 파악해 중분류(화면/PU) 수준으로 "
            "섹션을 나누어라. 섹션은 이후 각각 독립적으로 테스트케이스를 만들 단위이므로, 한 번에 "
            "검토하기 무리 없는 크기로 쪼개라. Appendix의 표는 관련된 섹션의 페이지 범위에 포함시켜라.\n\n"
            f"**중요: 1페이지부터 {n_pages}페이지까지 빠짐없이 어느 한 섹션에는 반드시 포함되어야 한다 "
            "(맨 앞 페이지들을 빠뜨리지 마라).**\n"
            "title은 반드시 페이지 내용을 근거로 한 의미있는 화면/기능명으로 작성하라 "
            "(예: '검술 훈련 - 결과창 UI'). '페이지 023' 같은 페이지 번호만 있는 제목은 금지한다.\n\n"
            f"{combined_all}"
        ),
    }]

    for attempt in range(1, 3):
        result = with_ollama_retries(
            ollama_chat_json, MODEL_TEXT, messages, json_schema=schema, num_ctx=SECTIONS_NUM_CTX
        )
        gaps = _sections_coverage_gaps(result["sections"], n_pages)
        if gaps:
            # 콘텐츠 없는 챕터 구분용 타이틀 슬라이드처럼 인접 섹션에 붙일 수 있는 갭은 자동으로 닫는다.
            result["sections"] = _autoclose_gap_pages(result["sections"], n_pages)
            gaps = _sections_coverage_gaps(result["sections"], n_pages)
        if not gaps:
            break
        print(f"  [경고] 섹션 인벤토리가 {len(gaps)}개 페이지를 누락함: {gaps} (시도 {attempt}/2)")
        messages = messages + [
            {"role": "assistant", "content": json.dumps(result, ensure_ascii=False)},
            {"role": "user", "content": f"다음 페이지들이 어떤 섹션에도 포함되지 않았다: {gaps}. "
                                         "이 페이지들도 반드시 어느 섹션에 포함되도록 sections 전체를 다시 출력하라."},
        ]
    else:
        result["sections"] = _autoclose_gap_pages(result["sections"], n_pages)
        gaps = _sections_coverage_gaps(result["sections"], n_pages)
        if gaps:
            raise OllamaError(f"섹션 인벤토리가 {n_pages}페이지 중 {len(gaps)}개를 계속 누락한다: {gaps}")

    lines = [f"기획서명: {pdf_name}", "", "번호 | 섹션 제목 | 페이지 범위 | 분류 후보"]
    for idx, s in enumerate(result["sections"], start=1):
        no = f"{idx:03d}"
        title = sanitize_title(s["title"])
        lines.append(f"{no} | {title} | p.{s['page_start']}-{s['page_end']} | {s['category_hint']}")
    text = "\n".join(lines) + "\n"
    write_text(out_path, text)
    return text


def parse_sections(sections_md):
    """sections.md를 [(no, title, page_start, page_end, category), ...]로 파싱."""
    out = []
    for line in sections_md.splitlines():
        m = re.match(r"^(\d{3})\s*\|\s*(.+?)\s*\|\s*p\.(\d+)-(\d+)\s*\|\s*(.+)$", line.strip())
        if m:
            out.append((m.group(1), m.group(2), int(m.group(3)), int(m.group(4)), m.group(5)))
    return out


def build_style_guide(tc_path):
    out_path = STATE_DIR / "style_guide.md"
    if out_path.exists():
        return read_text(out_path)

    raw = Path(tc_path).read_bytes()
    text = raw.decode("utf-8-sig", errors="replace")
    rows = list(csv.reader(io.StringIO(text)))
    header, data_rows = rows[0], rows[1:]
    sample = data_rows[:40]
    sample_csv = "\n".join(",".join(r) for r in [header] + sample)

    guide = ollama_chat(
        MODEL_TEXT,
        [{
            "role": "user",
            "content": (
                "다음은 기존 테스트케이스(TC) CSV 샘플이다. 이 파일에서 실제로 쓰인 것만 근거로 "
                "스타일 가이드를 작성하라 (내용/수치는 옮기지 말고 스타일만):\n"
                "1) 실제 발견된 약어 목록 (예: btn, PU, Lv. 등 — 파일에 있는 것만)\n"
                "2) 테스트 스텝/기대결과 문장 패턴 대표 예시 5~10개 인용\n"
                "3) 대/중/소분류 구성 방식\n"
                "마크다운으로 작성하라.\n\n"
                f"{sample_csv}"
            ),
        }],
    )
    write_text(out_path, guide)
    return guide


PROGRESS_TEMPLATE = """# PROGRESS
## Phase 0 — 초기화
- [x] sections.md / style_guide.md 작성
## Phase 1 — 섹션별 TC 생성
{section_lines}
## Phase 2 — 병합·최종 검증
- [ ] CSV 병합 → output/TC_{spec_name}.csv (No 전체 재부여)
- [ ] 의문점 병합 → output/의문점_{spec_name}.md
- [ ] 최종 검증 (validate_csv.py --final + RULES §7 체크리스트 → state/coverage_report.md)
- [ ] state/DONE 생성
"""


def build_progress(spec_name, sections):
    out_path = STATE_DIR / "PROGRESS.md"
    if out_path.exists():
        return
    section_lines = "\n".join(
        f"- [ ] {no} {title} (p.{ps}-{pe})" for no, title, ps, pe, _ in sections
    )
    write_text(out_path, PROGRESS_TEMPLATE.format(
        section_lines=section_lines, spec_name=spec_name,
    ))


def phase0_init():
    log("[Phase 0] 초기화 시작 (1/5: 입력 파일 확인)")
    pdf_path = find_input_pdf()
    tc_path = find_input_tc_csv()
    if pdf_path is None or tc_path is None:
        needs_human(
            "input/에 기획서 PDF 1개와 기존 TC(csv/xlsx) 1개가 있어야 한다. "
            f"현재 PDF: {list(INPUT_DIR.glob('*.pdf'))}, TC: {list(INPUT_DIR.glob('*.csv')) + list(INPUT_DIR.glob('*.xlsx'))}"
        )
        return False
    if tc_path.suffix.lower() == ".xlsx":
        needs_human(
            f"기존 TC가 xlsx({tc_path.name})다 — 현재 local_pipeline.py는 csv만 파싱한다. "
            "엑셀에서 CSV(UTF-8)로 내보내 input/에 넣거나, openpyxl 지원 추가가 필요하다."
        )
        return False

    spec_name = pdf_path.stem
    log(f"  기획서: {pdf_path.name} / 기존 TC: {tc_path.name}")

    log("[Phase 0] (2/5) 페이지 렌더링/텍스트 추출...")
    n_pages = render_pages(pdf_path)

    log(f"[Phase 0] (3/5) 비전 모델로 슬라이드 캡션 생성... (총 {n_pages}페이지)")
    caption_pages(n_pages)
    append_notes(
        f"PDF는 이미지 기반 슬라이드(Google Slides 내보내기)라 텍스트 레이어만으로는 "
        f"아트/UI 스펙을 파악할 수 없어 비전 모델({MODEL_VISION})로 페이지별 캡션을 생성해 "
        f"work/spec/pages/*.combined.md 에 텍스트와 함께 저장함. 캡션 품질을 한 번 훑어보는 것을 권장."
    )

    log("[Phase 0] (4/5) 섹션 인벤토리 작성...")
    sections_md = build_sections_md(spec_name, n_pages)
    sections = parse_sections(sections_md)
    if not sections:
        needs_human("state/sections.md 파싱 결과가 비어 있다 — 모델 출력 형식을 확인하라.")
        return False

    log("[Phase 0] (5/5) 스타일 가이드 작성 및 PROGRESS.md 생성...")
    build_style_guide(tc_path)
    build_progress(spec_name, sections)

    log(f"[Phase 0 완료] 섹션 {len(sections)}개로 분할 — Phase 1 시작")
    return True


# ── Phase 1: 섹션별 TC 생성 ─────────────────────────────────────────────
def read_progress_lines():
    return read_text(STATE_DIR / "PROGRESS.md").splitlines()


def write_progress_lines(lines):
    write_text(STATE_DIR / "PROGRESS.md", "\n".join(lines) + "\n")


def next_phase1_section():
    """PROGRESS.md의 Phase 1 항목 중 첫 미완료(`[ ]`) 섹션을 반환. 없으면 None."""
    in_phase1 = False
    for line in read_progress_lines():
        if line.startswith("## Phase 1"):
            in_phase1 = True
            continue
        if line.startswith("## Phase 2"):
            break
        if not in_phase1:
            continue
        m = re.match(r"^- \[ \] (\d{3}) (.+?) \(p\.(\d+)-(\d+)\)$", line.strip())
        if m:
            return m.group(1), m.group(2), int(m.group(3)), int(m.group(4))
    return None


def phase1_counts():
    """Phase 1 섹션 중 (완료+블록됨, 전체) 개수를 반환한다. 진행률 표시용."""
    total = 0
    done = 0
    in_phase1 = False
    for line in read_progress_lines():
        if line.startswith("## Phase 1"):
            in_phase1 = True
            continue
        if line.startswith("## Phase 2"):
            break
        if not in_phase1:
            continue
        s = line.strip()
        if re.match(r"^- \[.\] \d{3} ", s):
            total += 1
            if s.startswith("- [x]") or s.startswith("- [!]"):
                done += 1
    return done, total


def mark_section_done(no):
    lines = read_progress_lines()
    for i, line in enumerate(lines):
        if re.match(rf"^- \[ \] {no} ", line.strip()):
            lines[i] = line.replace("[ ]", "[x]", 1)
            break
    write_progress_lines(lines)


def mark_section_blocked(no, reason):
    lines = read_progress_lines()
    for i, line in enumerate(lines):
        if re.match(rf"^- \[ \] {no} ", line.strip()):
            lines[i] = line.replace("[ ]", f"[!] BLOCKED — {reason} —", 1)
            break
    write_progress_lines(lines)


def cleanup_partial(no):
    """PROGRESS에 체크 안 됐는데 산출물이 있으면(직전 반복이 끊긴 것) 지우고 새로 시작."""
    for d in (TC_DIR, QUESTIONS_DIR):
        for f in d.glob(f"{no}_*"):
            print(f"  [복구] 불완전 산출물 삭제: {f}")
            f.unlink()


def section_context(page_start, page_end):
    parts = [read_text(SPEC_PAGES_DIR / f"{i:03d}.combined.md") for i in range(page_start, page_end + 1)]
    return "\n\n".join(parts)


TC_SCHEMA = {
    "type": "object",
    "properties": {
        "elements": {"type": "array", "items": {"type": "string"}},
        "test_cases": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "대분류": {"type": "string"},
                    "중분류": {"type": "string"},
                    "소분류": {"type": "string"},
                    "테스트 항목": {"type": "string"},
                    "사전조건": {"type": "string"},
                    "테스트 스텝": {"type": "string"},
                    "기대결과": {"type": "string"},
                    "비고": {"type": "string"},
                },
                "required": ["대분류", "중분류", "소분류", "테스트 항목",
                             "사전조건", "테스트 스텝", "기대결과", "비고"],
            },
        },
        "questions": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {"text": {"type": "string"}, "source": {"type": "string"}},
                "required": ["text", "source"],
            },
        },
    },
    "required": ["elements", "test_cases", "questions"],
}


def generate_section(no, title, page_start, page_end, style_guide, notes, error_feedback=None):
    ctx = section_context(page_start, page_end)
    messages = [
        {
            "role": "system",
            "content": (
                "너는 숙련된 QA 엔지니어다. 아래 RULES를 반드시 따라 테스트케이스와 의문점을 생성한다.\n\n"
                f"{rules_text()}\n\n## 스타일 가이드\n{style_guide}\n\n## 이전 반복이 남긴 노트\n{notes or '(없음)'}"
            ),
        },
        {
            "role": "user",
            "content": (
                f"섹션 {no} '{title}' (p.{page_start}-{page_end})의 기획서 내용이다. "
                "먼저 이 섹션의 기능/UI/아트 요소를 목록화(elements)하고, RULES에 따라 "
                "test_cases와 questions를 JSON으로 생성하라. test_cases에는 No를 포함하지 마라 "
                "(번호는 별도로 부여한다). elements의 모든 항목이 test_cases 또는 questions 중 "
                "한쪽에 반드시 반영되어야 한다. questions의 text 필드에는 질문 내용만 쓰고 "
                "출처(페이지/슬라이드 번호)는 절대 포함하지 마라 — 출처는 반드시 별도의 source "
                "필드에만 적어라 (예: text='군량미 최대치는?', source='p.5').\n\n"
                f"{ctx}"
            ),
        },
    ]
    if error_feedback:
        messages.append({
            "role": "user",
            "content": f"방금 생성한 CSV가 검증에 실패했다. 다음 오류를 고쳐서 test_cases를 다시 생성하라 (형식은 동일):\n{error_feedback}",
        })
    return ollama_chat_json(MODEL_TEXT, messages, json_schema=TC_SCHEMA)


def write_section_csv(no, title, test_cases):
    path = TC_DIR / f"{no}_{title}.csv"
    TC_DIR.mkdir(parents=True, exist_ok=True)
    buf = io.StringIO()
    writer = csv.writer(buf, lineterminator="\n")
    writer.writerow(CSV_HEADER)
    for i, row in enumerate(test_cases, start=1):
        writer.writerow([str(i)] + [row.get(f, "") for f in TC_FIELDS])
    path.write_bytes(b"\xef\xbb\xbf" + buf.getvalue().encode("utf-8"))
    return path


def write_section_questions(no, title, questions):
    path = QUESTIONS_DIR / f"{no}_{title}.md"
    QUESTIONS_DIR.mkdir(parents=True, exist_ok=True)
    if not questions:
        write_text(path, "(없음)\n")
        return path
    lines = [f"## {no} {title}"]
    for q in questions:
        # 모델이 text 안에 이미 "(출처: ...)"를 넣는 경우가 있어(지시해도 가끔 어김) 중복 방지로 제거한다.
        text = re.sub(r"\s*\(출처\s*[:：].*?\)\s*$", "", q["text"].strip())
        lines.append(f"- {text} (출처: {q['source']})")
    write_text(path, "\n".join(lines) + "\n")
    return path


def run_validate(csv_path, final=False):
    args = [sys.executable, str(ROOT / "scripts" / "validate_csv.py")]
    if final:
        args.append("--final")
    args.append(str(csv_path))
    # Windows 콘솔 기본 코드페이지(cp949 등)로 자식 프로세스가 한글을 출력하면
    # UTF-8 디코딩이 깨지므로, 자식 프로세스의 출력 인코딩을 명시적으로 고정한다.
    env = dict(os.environ, PYTHONIOENCODING="utf-8")
    proc = subprocess.run(args, capture_output=True, text=True,
                           encoding="utf-8", errors="replace", env=env)
    return proc.returncode == 0, proc.stdout + proc.stderr


def phase1_section():
    sel = next_phase1_section()
    if sel is None:
        return False  # Phase 1 완료 (더 이상 처리할 섹션 없음)
    no, title, page_start, page_end = sel
    done, total = phase1_counts()
    idx = done + 1
    pct = round(idx / total * 100) if total else 0
    log(f"[Phase 1] ({idx}/{total}, {pct}%) {no} {title} (p.{page_start}-{page_end}) — 시작")

    cleanup_partial(no)
    style_guide = read_text(STATE_DIR / "style_guide.md")
    notes = read_text(STATE_DIR / "NOTES.md")

    error_feedback = None
    for attempt in range(1, MAX_VALIDATE_RETRIES + 1):
        log(f"  {no} 생성 시도 {attempt}/{MAX_VALIDATE_RETRIES}...")
        try:
            result = generate_section(no, title, page_start, page_end, style_guide, notes, error_feedback)
            csv_path = write_section_csv(no, title, result["test_cases"])
            write_section_questions(no, title, result["questions"])
            ok, output = run_validate(csv_path)
        except OllamaError as e:
            # 검증 실패와 동일하게 취급해 같은 섹션을 재시도한다. 여기서 그대로 올려보내면
            # main()의 예외 처리가 파이프라인 전체를 죽여서, 이 섹션 이후 나머지 섹션이
            # 통째로 처리되지 못하는 문제가 있었다 (겉으로는 "첫 섹션만 처리하고 멈춤"으로 보임).
            ok = False
            output = f"Ollama 호출 오류: {e}"
        if ok:
            log(f"  {no} 검증 통과 (시도 {attempt})")
            break
        log(f"  {no} 검증 실패 (시도 {attempt}/{MAX_VALIDATE_RETRIES}):\n{output}")
        error_feedback = output
    else:
        reason = f"{MAX_VALIDATE_RETRIES}회 연속 검증 실패"
        mark_section_blocked(no, reason)
        append_notes(f"{no} {title}: {reason}. 마지막 오류:\n{error_feedback}")
        done, total = phase1_counts()
        log(f"[반복 요약] ({done}/{total}) 완료: (실패) {no} {title} | BLOCKED | 다음: 다음 섹션 진행")
        return True

    mark_section_done(no)
    done, total = phase1_counts()
    nxt = next_phase1_section()
    nxt_desc = f"{nxt[0]} {nxt[1]}" if nxt else "(Phase 1 완료)"
    log(f"[반복 요약] ({done}/{total}, {round(done/total*100) if total else 0}%) 완료: {no} {title} | 다음: {nxt_desc}")
    return True


# ── Phase 2: 병합·최종 검증 ──────────────────────────────────────────────
def phase2_item_status():
    for line in read_progress_lines():
        s = line.strip()
        if s.startswith("- [ ]") and ("CSV 병합" in s or "의문점 병합" in s
                                       or "최종 검증" in s or "state/DONE" in s):
            return s
    return None


def mark_phase2_done(marker_substr):
    lines = read_progress_lines()
    for i, line in enumerate(lines):
        if line.strip().startswith("- [ ]") and marker_substr in line:
            lines[i] = line.replace("[ ]", "[x]", 1)
            break
    write_progress_lines(lines)


def has_blocked_sections():
    return any("[!] BLOCKED" in line for line in read_progress_lines())


def spec_name_from_sections():
    first_line = read_text(STATE_DIR / "sections.md").splitlines()[0]
    return first_line.replace("기획서명:", "").strip()


def merge_csv(spec_name):
    files = sorted(TC_DIR.glob("*.csv"))
    out_path = OUTPUT_DIR / f"TC_{spec_name}.csv"
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    buf = io.StringIO()
    writer = csv.writer(buf, lineterminator="\n")
    writer.writerow(CSV_HEADER)
    n = 0
    for f in files:
        text = f.read_bytes().decode("utf-8-sig")
        rows = list(csv.reader(io.StringIO(text)))
        for row in rows[1:]:
            n += 1
            writer.writerow([str(n)] + row[1:])
    out_path.write_bytes(b"\xef\xbb\xbf" + buf.getvalue().encode("utf-8"))
    log(f"  {out_path} 생성 ({n}행)")
    return out_path


def merge_questions(spec_name):
    files = sorted(QUESTIONS_DIR.glob("*.md"))
    out_path = OUTPUT_DIR / f"의문점_{spec_name}.md"
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    chunks = []
    for f in files:
        content = read_text(f).strip()
        if content == "(없음)":
            continue
        chunks.append(content)
    merged = "\n\n".join(chunks) + "\n" if chunks else "(의문점 없음)\n"

    if len(chunks) > 1:
        try:
            merged = ollama_chat(
                MODEL_TEXT,
                [{
                    "role": "user",
                    "content": (
                        "다음은 섹션별 의문점 목록이다. 기획서 등장 순서를 유지하면서, "
                        "중복되거나 사실상 같은 질문은 출처를 병기해 하나로 합쳐라. "
                        "그 외 내용은 절대 바꾸지 마라. 마크다운으로 출력하라.\n\n" + merged
                    ),
                }],
            )
        except OllamaError as e:
            print(f"  [경고] 의문점 중복 병합용 LLM 호출 실패, 단순 병합으로 대체: {e}")

    write_text(out_path, merged)
    log(f"  {out_path} 생성")
    return out_path


def write_coverage_report(spec_name, tc_path):
    style_guide = read_text(STATE_DIR / "style_guide.md")
    sections_md = read_text(STATE_DIR / "sections.md")
    tc_sample = tc_path.read_bytes().decode("utf-8-sig")[:6000]
    report = with_ollama_retries(
        ollama_chat,
        MODEL_TEXT,
        [{
            "role": "user",
            "content": (
                "다음 RULES.md §7 체크리스트 항목들을 아래 자료를 근거로 하나씩 점검하고, "
                "각 항목에 대해 통과/의심/불통과와 근거를 markdown 체크리스트로 작성하라. "
                "확신이 없으면 '사람 재검토 필요'라고 솔직히 적어라.\n\n"
                "체크리스트:\n"
                "- 기획문서의 모든 섹션·항목이 TC 또는 의문점 중 하나로 커버됨\n"
                "- 아트/리소스 스펙이 기획서에 명시된 범위에서 빠짐없이 TC화됨\n"
                "- \"OO와 동일\" 항목이 생략 없이 각 섹션에 자기완결적으로 작성됨\n"
                "- 분류 체계·문장 스타일이 기존 TC(style_guide)와 일치함\n"
                "- 비고는 정의된 특이점 용도로만 사용되고 일반 TC에서는 비어 있음\n"
                "- 각 의문점에 기획서 출처가 있음\n\n"
                f"## 섹션 인벤토리\n{sections_md}\n\n## 스타일 가이드\n{style_guide}\n\n"
                f"## 최종 TC CSV 샘플(앞부분)\n{tc_sample}"
            ),
        }],
    )
    write_text(STATE_DIR / "coverage_report.md", report)
    return report


PHASE2_STEP_ORDER = ["CSV 병합", "의문점 병합", "최종 검증", "state/DONE"]


def phase2_step_index(item):
    for i, key in enumerate(PHASE2_STEP_ORDER, start=1):
        if key in item:
            return i
    return 0


def phase2_merge():
    item = phase2_item_status()
    if item is None:
        return False

    if has_blocked_sections():
        blocked = [line.strip() for line in read_progress_lines() if "[!] BLOCKED" in line]
        log("[경고] BLOCKED 섹션이 남아 있으나 사람 개입 없이 계속 진행합니다:\n" + "\n".join(blocked))

    spec_name = spec_name_from_sections()
    step_idx = phase2_step_index(item)
    log(f"[Phase 2] ({step_idx}/{len(PHASE2_STEP_ORDER)}) {item}")

    if "CSV 병합" in item:
        merge_csv(spec_name)
        mark_phase2_done("CSV 병합")
    elif "의문점 병합" in item:
        merge_questions(spec_name)
        mark_phase2_done("의문점 병합")
    elif "최종 검증" in item:
        tc_path = OUTPUT_DIR / f"TC_{spec_name}.csv"
        ok, output = run_validate(tc_path, final=True)
        print(output)
        if not ok:
            log(f"[경고] 병합된 최종 CSV({tc_path})가 검증에 실패했으나 계속 진행합니다:\n{output}")
        write_coverage_report(spec_name, tc_path)
        mark_phase2_done("최종 검증")
    elif "state/DONE" in item:
        tc_path = OUTPUT_DIR / f"TC_{spec_name}.csv"
        q_path = OUTPUT_DIR / f"의문점_{spec_name}.md"
        n_tc = len(tc_path.read_bytes().decode("utf-8-sig").splitlines()) - 1
        write_text(STATE_DIR / "DONE", (
            f"완료: {spec_name}\n"
            f"TC: {tc_path.relative_to(ROOT)} ({n_tc}건)\n"
            f"의문점: {q_path.relative_to(ROOT)}\n"
            f"검수자 확인 사항: state/coverage_report.md, work/spec/pages/*.vision.md(비전 캡션 정확도)\n"
        ))
        mark_phase2_done("state/DONE")
        archive_path = archive_run(spec_name)
        elapsed = int(time.time() - _RUN_START)
        h, rem = divmod(elapsed, 3600)
        m, s = divmod(rem, 60)
        log(f"  산출물 백업 → {archive_path.relative_to(ROOT)}")
        log(f"[반복 요약] (4/4, 100%) 완료: state/DONE 생성 | 총 소요시간 {h:02d}:{m:02d}:{s:02d} | TC {n_tc}건")

    return True


def archive_run(spec_name):
    """다음 업로드가 output/·work/·state/를 지우기 전에, 완료된 산출물 전체를
    archive/<기획서명>_<타임스탬프>/ 밑에 그대로 복사해 보존한다."""
    ts = time.strftime("%Y%m%d_%H%M%S")
    dest = ARCHIVE_DIR / f"{spec_name}_{ts}"
    dest.mkdir(parents=True, exist_ok=True)

    if OUTPUT_DIR.exists():
        shutil.copytree(OUTPUT_DIR, dest / "output", dirs_exist_ok=True)
    if WORK_DIR.exists():
        shutil.copytree(WORK_DIR, dest / "work", dirs_exist_ok=True)

    state_dest = dest / "state"
    state_dest.mkdir(parents=True, exist_ok=True)
    for name in ("coverage_report.md", "sections.md", "style_guide.md",
                 "PROGRESS.md", "NOTES.md", "DONE"):
        src = STATE_DIR / name
        if src.exists():
            shutil.copy2(src, state_dest / name)

    return dest


# ── 메인 루프 ────────────────────────────────────────────────────────────
def run_one_unit():
    """한 단위(Phase 0 전체 / Phase 1 섹션 하나 / Phase 2 항목 하나)를 처리한다."""
    if (STATE_DIR / "NEEDS_HUMAN").exists():
        log("[중단] state/NEEDS_HUMAN 존재 — 해결 후 삭제하고 재실행하라.")
        return False
    if (STATE_DIR / "DONE").exists():
        log("[완료] state/DONE 존재.")
        return False

    if not (STATE_DIR / "PROGRESS.md").exists():
        return phase0_init()
    if next_phase1_section() is not None:
        return phase1_section()
    return phase2_merge()


def main():
    once = "--once" in sys.argv[1:]
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    WORK_DIR.mkdir(parents=True, exist_ok=True)

    while True:
        try:
            progressed = run_one_unit()
        except OllamaError as e:
            # 여기까지 올라왔다는 건 with_ollama_retries()로도 회복 못한 지속 장애
            # (Ollama 서버가 완전히 죽었거나 모델이 안 올라온 경우 등)라는 뜻이다.
            # 그냥 종료하면 웹 UI에 "STOPPED — 로그를 확인하라"는 안내만 뜨고 원인이
            # 안 보이므로, NEEDS_HUMAN으로 표시해 배너에 실제 원인이 뜨도록 한다.
            log(f"[오류] {e}")
            needs_human(f"Ollama 호출이 반복 재시도 후에도 실패했다: {e}")
            sys.exit(1)
        if not progressed:
            break
        if once:
            break
        if (STATE_DIR / "DONE").exists() or (STATE_DIR / "NEEDS_HUMAN").exists():
            break


if __name__ == "__main__":
    main()
