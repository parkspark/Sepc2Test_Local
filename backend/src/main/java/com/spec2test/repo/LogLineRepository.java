package com.spec2test.repo;

import com.spec2test.domain.LogLine;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LogLineRepository extends JpaRepository<LogLine, Long> {

    List<LogLine> findByRunIdAndIdGreaterThanOrderById(Long runId, Long afterId);

    List<LogLine> findByRunIdOrderById(Long runId);
}
