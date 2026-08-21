package com.spec2test.api;

import com.spec2test.csv.TcCsvWriter;
import com.spec2test.domain.Document;
import com.spec2test.domain.DocumentKind;
import com.spec2test.domain.Run;
import com.spec2test.domain.TestCase;
import com.spec2test.repo.DocumentRepository;
import com.spec2test.repo.RunRepository;
import com.spec2test.repo.TestCaseRepository;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class OutputController {

    private final RunRepository runRepository;
    private final TestCaseRepository testCaseRepository;
    private final DocumentRepository documentRepository;
    private final TcCsvWriter csvWriter;

    public OutputController(RunRepository runRepository, TestCaseRepository testCaseRepository,
            DocumentRepository documentRepository, TcCsvWriter csvWriter) {
        this.runRepository = runRepository;
        this.testCaseRepository = testCaseRepository;
        this.documentRepository = documentRepository;
        this.csvWriter = csvWriter;
    }

    @GetMapping("/outputs")
    public Map<String, Object> outputs() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("csv", null);
        response.put("markdown", null);
        response.put("coverage", null);
        response.put("csv_filename", null);
        response.put("markdown_filename", null);

        Optional<Run> currentRun = runRepository.findTopByOrderByIdDesc();
        if (currentRun.isEmpty()) {
            return response;
        }
        Run run = currentRun.get();

        List<TestCase> rows = testCaseRepository.findByRunIdOrderByGlobalNo(run.getId());
        if (!rows.isEmpty()) {
            response.put("csv", rows.stream().map(this::toRowMap).toList());
            response.put("csv_filename", csvFilename(run));
        }

        documentRepository.findByRunIdAndKind(run.getId(), DocumentKind.MERGED_QUESTIONS).ifPresent(doc -> {
            response.put("markdown", doc.getContent());
            response.put("markdown_filename", markdownFilename(run));
        });

        documentRepository.findByRunIdAndKind(run.getId(), DocumentKind.COVERAGE_REPORT)
                .ifPresent(doc -> response.put("coverage", doc.getContent()));

        return response;
    }

    @GetMapping("/download/{fileType}")
    public ResponseEntity<byte[]> download(@PathVariable String fileType) {
        Optional<Run> currentRun = runRepository.findTopByOrderByIdDesc();
        if (currentRun.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Run run = currentRun.get();

        if ("csv".equals(fileType)) {
            List<TestCase> rows = testCaseRepository.findByRunIdOrderByGlobalNo(run.getId());
            if (rows.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            byte[] body = csvWriter.write(rows);
            return fileResponse(body, csvFilename(run), "text/csv");
        }
        if ("markdown".equals(fileType)) {
            Optional<Document> doc = documentRepository.findByRunIdAndKind(run.getId(), DocumentKind.MERGED_QUESTIONS);
            if (doc.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            byte[] body = doc.get().getContent().getBytes(StandardCharsets.UTF_8);
            return fileResponse(body, markdownFilename(run), "text/markdown");
        }
        return ResponseEntity.notFound().build();
    }

    private ResponseEntity<byte[]> fileResponse(byte[] body, String filename, String contentType) {
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8)
                .build();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(disposition);
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.parseMediaType(contentType + "; charset=UTF-8"))
                .body(body);
    }

    private Map<String, String> toRowMap(TestCase row) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("No", String.valueOf(row.getGlobalNo()));
        map.put("대분류", row.getCategoryMajor());
        map.put("중분류", row.getCategoryMid());
        map.put("소분류", row.getCategoryMinor());
        map.put("테스트 항목", row.getTestItem());
        map.put("사전조건", row.getPrecondition());
        map.put("테스트 스텝", row.getTestSteps());
        map.put("기대결과", row.getExpectedResult());
        map.put("비고", row.getRemark());
        return map;
    }

    private String csvFilename(Run run) {
        return "TC_" + run.getSpecName() + ".csv";
    }

    private String markdownFilename(Run run) {
        return "의문점_" + run.getSpecName() + ".md";
    }
}
