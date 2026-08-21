package com.spec2test.csv;

import com.spec2test.llm.dto.TestCaseEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * scripts/validate_csv.py의 규칙을 그대로 이식한 검증기. 파일이 아니라 생성된 test_case 목록을
 * 곧바로 검증한다 (섹션 검증/최종 병합 검증 모두 이 클래스를 공유한다).
 */
@Component
public class TcValidator {

    private static final Set<String> PLACEHOLDERS = Set.of("-", "N/A", "n/a", "없음", "해당없음", "해당 없음", "x", "X");
    // "…출력된다. 1. 요소A 2. 요소B" 같은 번호 나열형 종결 허용
    private static final Pattern ENUM_TAIL = Pattern.compile("(?:^|\\s)\\d{1,2}\\.\\s*[^.]*$");
    private static final Pattern SAME_AS = Pattern.compile("[과와]\\s*동일");

    public record ValidationResult(List<String> errors, List<String> warnings) {
        public boolean isValid() {
            return errors.isEmpty();
        }
    }

    public ValidationResult validate(List<TestCaseEntry> rows, boolean finalCheck) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (rows.isEmpty()) {
            if (finalCheck) {
                errors.add("데이터 행 없음");
            }
            return new ValidationResult(errors, warnings);
        }

        int remarkFilled = 0;
        for (int i = 0; i < rows.size(); i++) {
            int n = i + 2; // n = CSV 레코드 번호(헤더=1)와 동일한 관례
            TestCaseEntry row = rows.get(i);

            checkBlank(row.categoryMajor(), "대분류", n, errors);
            checkBlank(row.categoryMid(), "중분류", n, errors);
            checkBlank(row.categoryMinor(), "소분류", n, errors);
            checkBlank(row.testItem(), "테스트 항목", n, errors);
            checkBlank(row.testSteps(), "테스트 스텝", n, errors);
            checkBlank(row.expectedResult(), "기대결과", n, errors);

            String pre = row.precondition() == null ? "" : row.precondition().strip();
            if (PLACEHOLDERS.contains(pre)) {
                errors.add(n + "행: 사전조건에 placeholder '" + pre + "' — 조건이 없으면 빈 값으로");
            }

            checkSentenceEnding(row.testSteps(), "테스트 스텝", n, errors, warnings);
            checkSentenceEnding(row.expectedResult(), "기대결과", n, errors, warnings);

            if (row.remark() != null && !row.remark().strip().isEmpty()) {
                remarkFilled++;
            }
        }

        double ratio = (double) remarkFilled / rows.size();
        if (ratio > 0.3) {
            warnings.add("비고 작성 비율 %d/%d — 비고는 특이점 전용, 남용 여부 확인".formatted(remarkFilled, rows.size()));
        }

        return new ValidationResult(errors, warnings);
    }

    /** 검증 실패를 LLM 재시도 피드백으로 되돌려주기 위한 문구 (run_validate의 stdout+stderr 대응). */
    public String formatOutput(ValidationResult result) {
        StringBuilder sb = new StringBuilder();
        for (String e : result.errors()) {
            sb.append("[ERROR] ").append(e).append("\n");
        }
        for (String w : result.warnings()) {
            sb.append("[WARN]  ").append(w).append("\n");
        }
        return sb.toString();
    }

    private void checkBlank(String value, String columnName, int n, List<String> errors) {
        if (value == null || value.isBlank()) {
            errors.add(n + "행: '" + columnName + "' 비어 있음 (fill-down — 모든 행에 값 반복 기재)");
        }
    }

    private void checkSentenceEnding(String value, String columnName, int n, List<String> errors,
            List<String> warnings) {
        if (value == null) {
            return;
        }
        String v = value.strip();
        if (!v.isEmpty() && !v.endsWith(".") && !ENUM_TAIL.matcher(v).find()) {
            String tail = v.length() > 20 ? v.substring(v.length() - 20) : v;
            errors.add(n + "행: '" + columnName + "'가 마침표로 끝나지 않음: …" + "'" + tail + "'");
        }
        if (SAME_AS.matcher(v).find()) {
            warnings.add(n + "행: '" + columnName + "'에 '~와 동일' 표현 — 참조 생략 금지, 자기완결로 풀어쓸 것");
        }
    }
}
