package com.spec2test.repo;

import com.spec2test.domain.Document;
import com.spec2test.domain.DocumentKind;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    Optional<Document> findByRunIdAndKind(Long runId, DocumentKind kind);
}
