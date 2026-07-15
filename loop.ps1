# TC 랄프 루프 러너 (Windows PowerShell) — 로컬 LLM(Ollama) 경로
# scripts/local_pipeline.py가 Ollama로 PROMPT.md/RULES.md와 동일한 절차를 수행한다.
# (원래의 Claude Code CLI 경로가 필요하면 PROMPT.md/RULES.md를 참고해 `claude -p
#  --dangerously-skip-permissions`를 호출하도록 되돌리면 된다.)
# 사용법: .\loop.ps1            (기본 40회)
#         .\loop.ps1 -MaxIterations 60
param([int]$MaxIterations = 40)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8
Set-Location $PSScriptRoot

for ($i = 1; $i -le $MaxIterations; $i++) {
    if (Test-Path "state/DONE") {
        Write-Host "[loop] 완료 — state/DONE:"
        Get-Content "state/DONE"
        exit 0
    }
    if (Test-Path "state/NEEDS_HUMAN") {
        Write-Host "[loop] 사람 개입 필요 — state/NEEDS_HUMAN:"
        Get-Content "state/NEEDS_HUMAN"
        Write-Host "[loop] 해결 후 state/NEEDS_HUMAN 파일을 삭제하고 재실행하세요."
        exit 1
    }
    Write-Host "=============================================="
    Write-Host "=== Iteration $i / $MaxIterations — $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
    Write-Host "=============================================="
    python scripts/local_pipeline.py --once
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[loop] local_pipeline.py가 오류로 종료됨 (exit $LASTEXITCODE)"
        exit $LASTEXITCODE
    }
}

Write-Host "[loop] 최대 반복($MaxIterations) 도달 — state/PROGRESS.md 확인 후 재실행하면 이어서 진행됩니다."
