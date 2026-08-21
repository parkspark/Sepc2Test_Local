package com.spec2test.api;

import com.spec2test.api.dto.ErrorResponse;
import com.spec2test.api.dto.StatusResponse;
import com.spec2test.api.dto.UploadResponse;
import com.spec2test.domain.Run;
import com.spec2test.domain.RunStatus;
import com.spec2test.domain.Upload;
import com.spec2test.domain.UploadKind;
import com.spec2test.pipeline.PipelineService;
import com.spec2test.pipeline.ProgressAssembler;
import com.spec2test.repo.RunRepository;
import com.spec2test.repo.UploadRepository;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class PipelineController {

    private final RunRepository runRepository;
    private final UploadRepository uploadRepository;
    private final PipelineService pipelineService;
    private final ProgressAssembler progressAssembler;

    public PipelineController(RunRepository runRepository, UploadRepository uploadRepository,
            PipelineService pipelineService, ProgressAssembler progressAssembler) {
        this.runRepository = runRepository;
        this.uploadRepository = uploadRepository;
        this.pipelineService = pipelineService;
        this.progressAssembler = progressAssembler;
    }

    @GetMapping("/status")
    public StatusResponse status() {
        Optional<Run> currentRun = runRepository.findTopByOrderByIdDesc();
        if (currentRun.isEmpty()) {
            return new StatusResponse("IDLE", "", null);
        }
        Run run = currentRun.get();
        String status = switch (run.getStatus()) {
            case CREATED, RUNNING -> "RUNNING";
            case STOPPED, FAILED -> "STOPPED";
            case NEEDS_HUMAN -> "NEEDS_HUMAN";
            case COMPLETED -> "COMPLETED";
        };
        String message = switch (run.getStatus()) {
            case NEEDS_HUMAN -> run.getNeedsHumanReason() == null ? "" : run.getNeedsHumanReason();
            case COMPLETED -> run.getDoneSummary() == null ? "" : run.getDoneSummary();
            case STOPPED, FAILED -> "Pipeline stopped or failed. Check the log output.";
            default -> "";
        };
        return new StatusResponse(status, message, progressAssembler.build(run));
    }

    @PostMapping("/stop")
    public ResponseEntity<?> stop() {
        boolean stopped = pipelineService.stop();
        if (stopped) {
            return ResponseEntity.ok(new UploadResponse("STOPPED", "Pipeline stopped successfully."));
        }
        return ResponseEntity.ok(new UploadResponse("IDLE", "Pipeline is not running."));
    }

    @PostMapping("/resume")
    public ResponseEntity<?> resume() {
        if (pipelineService.isRunning()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("Pipeline is already running"));
        }
        Optional<Run> currentRun = runRepository.findTopByOrderByIdDesc();
        if (currentRun.isEmpty()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("No run to resume"));
        }
        try {
            pipelineService.resume(currentRun.get().getId());
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
        return ResponseEntity.ok(new UploadResponse("RUNNING", "Pipeline resumed successfully."));
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(
            @RequestParam("pdf") MultipartFile pdf,
            @RequestParam(value = "csv", required = false) MultipartFile csv) throws IOException {

        if (pipelineService.isRunning()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("Pipeline is already running"));
        }
        if (pdf == null || pdf.isEmpty()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("PDF file is required"));
        }

        String pdfName = safeFilename(pdf.getOriginalFilename());
        if (!pdfName.toLowerCase().endsWith(".pdf")) {
            return ResponseEntity.badRequest().body(new ErrorResponse("기획서 파일은 .pdf 확장자여야 한다."));
        }

        String csvName = null;
        if (csv != null && !csv.isEmpty()) {
            csvName = safeFilename(csv.getOriginalFilename());
            String lower = csvName.toLowerCase();
            if (!(lower.endsWith(".csv") || lower.endsWith(".xlsx"))) {
                return ResponseEntity.badRequest().body(new ErrorResponse("스타일 가이드 파일은 .csv 또는 .xlsx 확장자여야 한다."));
            }
        }

        String specName = pdfName.substring(0, pdfName.length() - 4);
        Run run = new Run();
        run.setSpecName(specName);
        run.setStatus(RunStatus.CREATED);
        run = runRepository.save(run);

        Upload pdfUpload = new Upload();
        pdfUpload.setRunId(run.getId());
        pdfUpload.setKind(UploadKind.SPEC_PDF);
        pdfUpload.setFilename(pdfName);
        pdfUpload.setContentType(pdf.getContentType());
        pdfUpload.setContent(pdf.getBytes());
        uploadRepository.save(pdfUpload);

        if (csvName != null) {
            Upload csvUpload = new Upload();
            csvUpload.setRunId(run.getId());
            csvUpload.setKind(UploadKind.REFERENCE_CSV);
            csvUpload.setFilename(csvName);
            csvUpload.setContentType(csv.getContentType());
            csvUpload.setContent(csv.getBytes());
            uploadRepository.save(csvUpload);
        } else {
            var previous = uploadRepository.findFirstByKindOrderByIdDesc(UploadKind.REFERENCE_CSV);
            if (previous.isEmpty()) {
                return ResponseEntity.badRequest().body(new ErrorResponse(
                        "Reference CSV/style guide is missing in the workspace. Please upload one."));
            }
            Upload reused = new Upload();
            reused.setRunId(run.getId());
            reused.setKind(UploadKind.REFERENCE_CSV);
            reused.setFilename(previous.get().getFilename());
            reused.setContentType(previous.get().getContentType());
            reused.setContent(previous.get().getContent());
            uploadRepository.save(reused);
        }

        pipelineService.start(run.getId());

        return ResponseEntity.ok(new UploadResponse("RUNNING", "Pipeline started successfully."));
    }

    /**
     * 경로 구분자·상위 디렉토리 이동만 제거하고 한글 등 유니코드 파일명은 그대로 보존한다
     * (기존 app.py의 safe_upload_filename과 동일한 정책).
     */
    private static String safeFilename(String filename) {
        if (filename == null) {
            return "upload";
        }
        String normalized = filename.replace('\\', '/').replace("..", "");
        String base = Paths.get(normalized).getFileName() != null
                ? Paths.get(normalized).getFileName().toString()
                : normalized;
        return base.isBlank() ? "upload" : base;
    }
}
