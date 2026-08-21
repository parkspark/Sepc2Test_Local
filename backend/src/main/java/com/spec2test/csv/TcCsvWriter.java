package com.spec2test.csv;

import com.spec2test.domain.TestCase;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Component;

/**
 * 9컬럼 TC CSV를 UTF-8 BOM + LF로 직렬화한다 (write_section_csv/merge_csv 이식).
 * 엑셀 한글 호환을 위해 BOM이 반드시 필요하다.
 */
@Component
public class TcCsvWriter {

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final String[] HEADER = {
            "No", "대분류", "중분류", "소분류", "테스트 항목", "사전조건", "테스트 스텝", "기대결과", "비고"
    };

    public byte[] write(List<TestCase> rows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            out.write(UTF8_BOM);
            CSVFormat format = CSVFormat.DEFAULT.builder()
                    .setHeader(HEADER)
                    .setRecordSeparator("\n")
                    .get();
            try (CSVPrinter printer = new CSVPrinter(
                    new OutputStreamWriter(out, StandardCharsets.UTF_8), format)) {
                for (TestCase row : rows) {
                    printer.printRecord(
                            row.getGlobalNo(),
                            row.getCategoryMajor(),
                            row.getCategoryMid(),
                            row.getCategoryMinor(),
                            row.getTestItem(),
                            row.getPrecondition(),
                            row.getTestSteps(),
                            row.getExpectedResult(),
                            row.getRemark());
                }
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
