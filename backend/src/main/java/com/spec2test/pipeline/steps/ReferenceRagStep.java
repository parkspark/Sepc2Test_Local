package com.spec2test.pipeline.steps;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spec2test.domain.ReferenceChunk;
import com.spec2test.domain.Upload;
import com.spec2test.domain.UploadKind;
import com.spec2test.repo.ReferenceChunkRepository;
import com.spec2test.repo.UploadRepository;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 전체 참고 TC를 행 단위로 인덱싱하고, 현재 기획 섹션에 가장 가까운 사례만 반환한다.
 *
 * <p>EmbeddingModel이 있으면 cosine similarity와 토큰 점수를 혼합한다. 임베딩 모델을 pull하지 않았거나
 * API 제공자가 embedding을 지원하지 않는 경우에도 키워드 검색으로 자동 폴백하므로 TC 생성이 중단되지 않는다.
 * 벡터는 외부 벡터 DB 없이 이 테이블에 JSON으로 보관한다.</p>
 */
@Component
public class ReferenceRagStep {

    private static final Logger log = LoggerFactory.getLogger(ReferenceRagStep.class);
    private static final Pattern TOKEN_SPLITTER = Pattern.compile("[^\\p{L}\\p{N}_]+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final int MAX_CHUNK_CHARS = 4_000;

    private final UploadRepository uploadRepository;
    private final ReferenceChunkRepository chunkRepository;
    private final ObjectProvider<EmbeddingModel> embeddingModels;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReferenceRagStep(UploadRepository uploadRepository, ReferenceChunkRepository chunkRepository,
            ObjectProvider<EmbeddingModel> embeddingModels) {
        this.uploadRepository = uploadRepository;
        this.chunkRepository = chunkRepository;
        this.embeddingModels = embeddingModels;
    }

    /** 실행 재개 시 이미 영속화한 인덱스를 그대로 재사용한다. */
    public int index(Long runId) {
        long existing = chunkRepository.countByRunId(runId);
        if (existing > 0) {
            return Math.toIntExact(existing);
        }

        Upload upload = uploadRepository.findByRunIdAndKind(runId, UploadKind.REFERENCE_CSV)
                .orElseThrow(() -> new IllegalStateException("reference CSV upload not found for run " + runId));
        List<String> chunks = parseCsv(upload.getContent());
        EmbeddingModel embeddingModel = embeddingModels.getIfUnique();
        boolean useEmbeddings = embeddingModel != null;
        List<ReferenceChunk> entities = new ArrayList<>();
        int chunkNo = 1;
        for (String content : chunks) {
            ReferenceChunk chunk = new ReferenceChunk();
            chunk.setRunId(runId);
            chunk.setChunkNo(chunkNo++);
            chunk.setContent(content);
            if (useEmbeddings) {
                try {
                    chunk.setEmbedding(writeEmbedding(embeddingModel.embed(content)));
                } catch (RuntimeException e) {
                    useEmbeddings = false;
                    log.warn("RAG 임베딩을 사용할 수 없어 키워드 검색으로 전환합니다: {}", e.getMessage());
                }
            }
            entities.add(chunk);
        }
        chunkRepository.saveAll(entities);
        return entities.size();
    }

    /** 검색 결과가 없으면 빈 문자열을 돌려 프롬프트에서 해당 블록을 생략한다. */
    public String retrieveExamples(Long runId, String query, int limit) {
        List<ReferenceChunk> chunks = chunkRepository.findByRunIdOrderByChunkNo(runId);
        if (chunks.isEmpty() || query == null || query.isBlank()) {
            return "";
        }

        float[] queryEmbedding = tryEmbed(query);
        Set<String> queryTokens = tokens(query);
        List<ScoredChunk> scored = new ArrayList<>();
        for (ReferenceChunk chunk : chunks) {
            double lexical = jaccard(queryTokens, tokens(chunk.getContent()));
            double semantic = queryEmbedding == null ? Double.NaN : cosine(queryEmbedding, readEmbedding(chunk.getEmbedding()));
            double score = Double.isNaN(semantic) ? lexical : (semantic * 0.8 + lexical * 0.2);
            if (score > 0.03d) {
                scored.add(new ScoredChunk(chunk, score));
            }
        }
        return scored.stream()
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                .limit(limit)
                .map(s -> "[참고 TC %03d | 유사도 %.2f]\n%s".formatted(
                        s.chunk().getChunkNo(), s.score(), s.chunk().getContent()))
                .reduce((a, b) -> a + "\n\n" + b)
                .orElse("");
    }

    private List<String> parseCsv(byte[] csvBytes) {
        String text = new String(csvBytes, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        try (CSVParser parser = CSVFormat.DEFAULT.builder().build().parse(new StringReader(text))) {
            List<CSVRecord> records = parser.getRecords();
            if (records.size() < 2) {
                return List.of();
            }
            CSVRecord header = records.get(0);
            List<String> chunks = new ArrayList<>();
            for (int row = 1; row < records.size(); row++) {
                CSVRecord record = records.get(row);
                StringBuilder content = new StringBuilder();
                for (int col = 0; col < Math.min(header.size(), record.size()); col++) {
                    String value = record.get(col).strip();
                    if (!value.isBlank()) {
                        content.append(header.get(col).strip()).append(": ").append(value).append('\n');
                    }
                }
                if (!content.isEmpty()) {
                    chunks.add(content.substring(0, Math.min(content.length(), MAX_CHUNK_CHARS)).strip());
                }
            }
            return chunks;
        } catch (IOException e) {
            throw new IllegalStateException("RAG 참고 CSV 파싱 실패", e);
        }
    }

    private float[] tryEmbed(String text) {
        EmbeddingModel model = embeddingModels.getIfUnique();
        if (model == null) {
            return null;
        }
        try {
            return model.embed(text);
        } catch (RuntimeException e) {
            log.debug("RAG 질의 임베딩 실패, 키워드 검색 사용: {}", e.getMessage());
            return null;
        }
    }

    private String writeEmbedding(float[] embedding) {
        try {
            return objectMapper.writeValueAsString(embedding);
        } catch (Exception e) {
            throw new IllegalStateException("RAG 임베딩 직렬화 실패", e);
        }
    }

    private float[] readEmbedding(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<float[]>() { });
        } catch (Exception e) {
            return null;
        }
    }

    private static Set<String> tokens(String value) {
        Set<String> result = new HashSet<>();
        for (String token : TOKEN_SPLITTER.split(value.toLowerCase(Locale.ROOT))) {
            if (token.length() >= 2) {
                result.add(token);
            }
        }
        return result;
    }

    private static double jaccard(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0d;
        }
        Set<String> intersection = new HashSet<>(left);
        intersection.retainAll(right);
        Set<String> union = new HashSet<>(left);
        union.addAll(right);
        return (double) intersection.size() / union.size();
    }

    private static double cosine(float[] left, float[] right) {
        if (left == null || right == null || left.length != right.length || left.length == 0) {
            return Double.NaN;
        }
        double dot = 0d;
        double leftNorm = 0d;
        double rightNorm = 0d;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        return leftNorm == 0d || rightNorm == 0d ? Double.NaN : dot / Math.sqrt(leftNorm * rightNorm);
    }

    private record ScoredChunk(ReferenceChunk chunk, double score) { }
}
