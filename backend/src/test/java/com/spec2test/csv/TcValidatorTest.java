package com.spec2test.csv;

import static org.assertj.core.api.Assertions.assertThat;

import com.spec2test.llm.dto.TestCaseEntry;
import java.util.List;
import org.junit.jupiter.api.Test;

class TcValidatorTest {

    private final TcValidator validator = new TcValidator();

    private static TestCaseEntry row(String major, String mid, String minor, String item,
            String precondition, String steps, String expected, String remark) {
        return new TestCaseEntry(major, mid, minor, item, precondition, steps, expected, remark);
    }

    private static TestCaseEntry validRow() {
        return row("전투", "검술 훈련", "결과창", "결과창 표시", "", "훈련을 종료한다.", "결과창이 표시된다.", "");
    }

    @Test
    void validRowsProduceNoErrors() {
        var result = validator.validate(List.of(validRow(), validRow()), false);
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void blankRequiredFieldIsError() {
        TestCaseEntry bad = row("", "검술 훈련", "결과창", "결과창 표시", "", "훈련을 종료한다.", "결과창이 표시된다.", "");
        var result = validator.validate(List.of(bad), false);
        assertThat(result.errors()).anyMatch(e -> e.contains("'대분류'") && e.contains("비어 있음"));
    }

    @Test
    void blankPreconditionIsAllowed() {
        var result = validator.validate(List.of(validRow()), false);
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void placeholderPreconditionIsError() {
        TestCaseEntry bad = row("전투", "검술 훈련", "결과창", "결과창 표시", "-", "훈련을 종료한다.", "결과창이 표시된다.", "");
        var result = validator.validate(List.of(bad), false);
        assertThat(result.errors()).anyMatch(e -> e.contains("placeholder"));
    }

    @Test
    void stepsNotEndingWithPeriodIsError() {
        TestCaseEntry bad = row("전투", "검술 훈련", "결과창", "결과창 표시", "", "훈련을 종료한다", "결과창이 표시된다.", "");
        var result = validator.validate(List.of(bad), false);
        assertThat(result.errors()).anyMatch(e -> e.contains("마침표로 끝나지 않음"));
    }

    @Test
    void enumTailIsExemptFromPeriodRule() {
        TestCaseEntry ok = row("전투", "검술 훈련", "결과창", "결과창 표시", "",
                "다음 순서로 진행한다. 1. 시작 2. 종료", "다음과 같은 요소가 출력된다. 1. 요소A 2. 요소B", "");
        var result = validator.validate(List.of(ok), false);
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void sameAsExpressionIsWarningNotError() {
        TestCaseEntry row = row("전투", "검술 훈련", "결과창", "결과창 표시", "", "검술 훈련과 동일하다.", "결과창이 표시된다.", "");
        var result = validator.validate(List.of(row), false);
        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).anyMatch(w -> w.contains("~와 동일"));
    }

    @Test
    void excessiveRemarkUsageIsWarning() {
        TestCaseEntry remarked = row("전투", "검술 훈련", "결과창", "결과창 표시", "", "훈련을 종료한다.", "결과창이 표시된다.", "특이사항 있음");
        var result = validator.validate(List.of(remarked, validRow(), validRow()), false);
        assertThat(result.warnings()).anyMatch(w -> w.contains("비고 작성 비율"));
    }

    @Test
    void emptyRowsIsErrorOnlyWhenFinal() {
        var notFinal = validator.validate(List.of(), false);
        assertThat(notFinal.errors()).isEmpty();

        var isFinal = validator.validate(List.of(), true);
        assertThat(isFinal.errors()).contains("데이터 행 없음");
    }
}
