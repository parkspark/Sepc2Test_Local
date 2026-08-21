package com.spec2test.repo;

import com.spec2test.domain.Run;
import com.spec2test.domain.RunStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RunRepository extends JpaRepository<Run, Long> {

    Optional<Run> findTopByOrderByIdDesc();

    List<Run> findByStatus(RunStatus status);
}
