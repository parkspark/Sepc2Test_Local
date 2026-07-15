#!/usr/bin/env python3
"""TC CSV 형식 검증기 — 랄프 루프 각 반복에서 실행.

사용법: python scripts/validate_csv.py [--final] <csv 파일...>
  --final : 최종 병합 CSV 검증 (UTF-8 BOM 필수)
종료 코드: 0 = 통과(경고 가능), 1 = 오류, 2 = 사용법 오류
"""
import csv
import io
import re
import sys

EXPECTED_HEADER = ["No", "대분류", "중분류", "소분류", "테스트 항목",
                   "사전조건", "테스트 스텝", "기대결과", "비고"]
REQUIRED = ["대분류", "중분류", "소분류", "테스트 항목", "테스트 스텝", "기대결과"]
PLACEHOLDERS = {"-", "N/A", "n/a", "없음", "해당없음", "해당 없음", "x", "X"}
# "…출력된다. 1. 요소A 2. 요소B" 같은 번호 나열형 종결 허용
ENUM_TAIL = re.compile(r"(?:^|\s)\d{1,2}\.\s*[^.]*$")


def validate(path, final=False):
    errors, warnings = [], []
    try:
        raw = open(path, "rb").read()
    except OSError as e:
        return [f"파일 열기 실패: {e}"], []

    has_bom = raw.startswith(b"\xef\xbb\xbf")
    if final and not has_bom:
        errors.append("UTF-8 BOM 없음 — 최종 CSV는 BOM 필수 (엑셀 한글 깨짐 방지)")
    elif not final and not has_bom:
        warnings.append("UTF-8 BOM 없음 — 섹션 CSV도 BOM 포함 권장")

    try:
        text = raw.decode("utf-8-sig")
    except UnicodeDecodeError:
        return errors + ["UTF-8로 디코딩 불가"], warnings

    rows = list(csv.reader(io.StringIO(text)))
    if not rows:
        return errors + ["빈 파일"], warnings

    header = rows[0]
    if header != EXPECTED_HEADER:
        errors.append(f"헤더 불일치: {header}")
        if any("Test Level" in h for h in header):
            errors.append("'Test Level' 컬럼 사용 금지")
        return errors, warnings  # 헤더가 다르면 이후 검사 무의미

    idx = {h: i for i, h in enumerate(header)}
    remark_filled = 0
    data_rows = rows[1:]

    for n, row in enumerate(data_rows, start=2):  # n = CSV 레코드 번호(헤더=1)
        if len(row) != 9:
            errors.append(f"{n}행: 컬럼 수 {len(row)}개 (9개 필요)")
            continue
        expect = n - 1
        if row[0].strip() != str(expect):
            errors.append(f"{n}행: No='{row[0]}' (기대값 {expect} — 1부터 연속 일련번호)")
        for col in REQUIRED:
            if not row[idx[col]].strip():
                errors.append(f"{n}행: '{col}' 비어 있음 (fill-down — 모든 행에 값 반복 기재)")
        pre = row[idx["사전조건"]].strip()
        if pre in PLACEHOLDERS:
            errors.append(f"{n}행: 사전조건에 placeholder '{pre}' — 조건이 없으면 빈 값으로")
        for col in ("테스트 스텝", "기대결과"):
            v = row[idx[col]].strip()
            if v and not v.endswith(".") and not ENUM_TAIL.search(v):
                errors.append(f"{n}행: '{col}'가 마침표로 끝나지 않음: …{v[-20:]!r}")
            if re.search(r"[과와]\s*동일", v):
                warnings.append(f"{n}행: '{col}'에 '~와 동일' 표현 — 참조 생략 금지, 자기완결로 풀어쓸 것")
        if row[idx["비고"]].strip():
            remark_filled += 1

    if not data_rows:
        if final:
            errors.append("데이터 행 없음")
    elif remark_filled / len(data_rows) > 0.3:
        warnings.append(f"비고 작성 비율 {remark_filled}/{len(data_rows)} — 비고는 특이점 전용, 남용 여부 확인")

    return errors, warnings


def main():
    args = [a for a in sys.argv[1:] if a != "--final"]
    final = "--final" in sys.argv[1:]
    if not args:
        print(__doc__)
        sys.exit(2)
    fail = False
    for p in args:
        errs, warns = validate(p, final)
        print(f"== {p} ==")
        for e in errs:
            print(f"  [ERROR] {e}")
        for w in warns:
            print(f"  [WARN]  {w}")
        if errs:
            fail = True
        else:
            print(f"  OK ({f'경고 {len(warns)}건' if warns else '통과'})")
    sys.exit(1 if fail else 0)


if __name__ == "__main__":
    main()
