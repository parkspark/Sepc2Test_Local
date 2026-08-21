package com.spec2test.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** 참고 TC 한 행을 검색 가능한 RAG 청크로 보관한다. */
@Entity
@Table(name = "reference_chunk", uniqueConstraints = @UniqueConstraint(columnNames = {"run_id", "chunk_no"}))
public class ReferenceChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "chunk_no", nullable = false)
    private Integer chunkNo;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** JSON float array. pgvector 없이도 PostgreSQL에 영속화하고 Java에서 cosine을 계산한다. */
    @Column(columnDefinition = "TEXT")
    private String embedding;

    public Long getId() { return id; }
    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }
    public Integer getChunkNo() { return chunkNo; }
    public void setChunkNo(Integer chunkNo) { this.chunkNo = chunkNo; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getEmbedding() { return embedding; }
    public void setEmbedding(String embedding) { this.embedding = embedding; }
}
