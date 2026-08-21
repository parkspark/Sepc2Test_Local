package com.spec2test.repo;

import com.spec2test.domain.Upload;
import com.spec2test.domain.UploadKind;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UploadRepository extends JpaRepository<Upload, Long> {

    Optional<Upload> findByRunIdAndKind(Long runId, UploadKind kind);

    Optional<Upload> findFirstByKindOrderByIdDesc(UploadKind kind);
}
