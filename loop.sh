#!/usr/bin/env bash
# TC 랄프 루프 러너 (WSL / Git Bash) — 로컬 LLM(Ollama) 경로
# scripts/local_pipeline.py가 Ollama로 PROMPT.md/RULES.md와 동일한 절차를 수행한다.
# (원래의 Claude Code CLI 경로가 필요하면 PROMPT.md/RULES.md를 참고해 `claude -p
#  --dangerously-skip-permissions`를 호출하도록 되돌리면 된다.)
# 사용법: ./loop.sh [최대반복수]   (기본 40)
set -u
cd "$(dirname "$0")"
MAX="${1:-40}"

for ((i = 1; i <= MAX; i++)); do
  if [[ -f state/DONE ]]; then
    echo "[loop] 완료 — state/DONE:"
    cat state/DONE
    exit 0
  fi
  if [[ -f state/NEEDS_HUMAN ]]; then
    echo "[loop] 사람 개입 필요 — state/NEEDS_HUMAN:"
    cat state/NEEDS_HUMAN
    echo "[loop] 해결 후 state/NEEDS_HUMAN 파일을 삭제하고 재실행하세요."
    exit 1
  fi
  echo "=============================================="
  echo "=== Iteration $i / $MAX — $(date '+%Y-%m-%d %H:%M:%S')"
  echo "=============================================="
  python3 scripts/local_pipeline.py --once || { echo "[loop] local_pipeline.py가 오류로 종료됨"; exit 1; }
done

echo "[loop] 최대 반복($MAX) 도달 — state/PROGRESS.md 확인 후 재실행하면 이어서 진행됩니다."
