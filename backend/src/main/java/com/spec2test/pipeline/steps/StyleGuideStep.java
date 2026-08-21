package com.spec2test.pipeline.steps;

import com.spec2test.domain.Document;
import com.spec2test.domain.DocumentKind;
import com.spec2test.domain.Upload;
import com.spec2test.domain.UploadKind;
import com.spec2test.llm.LlmGateway;
import com.spec2test.llm.PromptLibrary;
import com.spec2test.repo.DocumentRepository;
import com.spec2test.repo.UploadRepository;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

/**
 * 참고 TC CSV 앞 40행에서 스타일(약어/문장 패턴/분류 구성)만 추출한다 (build_style_guide 이식).
 * 내용·수치는 참고하지 않고 스타일만 반영하도록 프롬프트에서 명시한다.
 */
@Component
public class StyleGuideStep {

    private static final int SAMPLE_ROWS = 40;

    private final UploadRepository uploadRepository;
    private final DocumentRepository documentRepository;
    private final LlmGateway llmGateway;
    private final PromptLibrary prompts;

    public StyleGuideStep(UploadRepository uploadRepository, DocumentRepository documentRepository,
            LlmGateway llmGateway, PromptLibrary prompts) {
        this.uploadRepository = uploadRepository;
        this.documentRepository = documentRepository;
        this.llmGateway = llmGateway;
        this.prompts = prompts;
    }

    public String buildStyleGuide(Long runId) {
        var existing = documentRepository.findByRunIdAndKind(runId, DocumentKind.STYLE_GUIDE);
        if (existing.isPresent()) {
            return existing.get().getContent();
        }

        Upload csvUpload = uploadRepository.findByRunIdAndKind(runId, UploadKind.REFERENCE_CSV)
                .orElseThrow(() -> new IllegalStateException("reference CSV upload not found for run " + runId));
        String sampleCsv = buildSampleCsv(csvUpload.getContent());
        String userPrompt = prompts.render("style_guide", Map.of("sampleCsv", sampleCsv));
        String guide = llmGateway.text(null, userPrompt);

        Document document = new Document();
        document.setRunId(runId);
        document.setKind(DocumentKind.STYLE_GUIDE);
        document.setContent(guide);
        documentRepository.save(document);
        return guide;
    }

    private String buildSampleCsv(byte[] csvBytes) {
        String text = new String(csvBytes, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        try (CSVParser parser = CSVFormat.DEFAULT.builder().build().parse(new StringReader(text))) {
            List<CSVRecord> records = parser.getRecords();
            if (records.isEmpty()) {
                return "";
            }
            CSVRecord header = records.get(0);
            List<CSVRecord> sample = records.subList(1, Math.min(SAMPLE_ROWS + 1, records.size()));

            StringBuilder sb = new StringBuilder();
            sb.append(String.join(",", header)).append("\n");
            for (int i = 0; i < sample.size(); i++) {
                sb.append(String.join(",", sample.get(i)));
                if (i < sample.size() - 1) {
                    sb.append("\n");
                }
            }
            return sb.toString();
        } catch (IOException e) {
            throw new com.spec2test.llm.LlmException("참고 CSV 파싱 실패", e);
        }
    }
}
