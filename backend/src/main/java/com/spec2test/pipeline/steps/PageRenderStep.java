package com.spec2test.pipeline.steps;

import com.spec2test.config.Spec2TestProperties;
import com.spec2test.domain.Page;
import com.spec2test.repo.PageRepository;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

/**
 * PDF 각 페이지를 PNG로 렌더링하고 텍스트 레이어를 추출한다 (PyMuPDF 기반 render_pages 이식).
 * 이미 렌더링된 페이지(run_id, page_no 존재)는 건너뛰어 재실행 시 중복 작업을 피한다.
 */
@Component
public class PageRenderStep {

    private final PageRepository pageRepository;
    private final Spec2TestProperties properties;

    public PageRenderStep(PageRepository pageRepository, Spec2TestProperties properties) {
        this.pageRepository = pageRepository;
        this.properties = properties;
    }

    public int render(Long runId, byte[] pdfBytes) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            int pageCount = document.getNumberOfPages();
            PDFRenderer renderer = new PDFRenderer(document);

            for (int i = 0; i < pageCount; i++) {
                int pageNo = i + 1;
                if (pageRepository.existsByRunIdAndPageNo(runId, pageNo)) {
                    continue;
                }

                BufferedImage image = renderer.renderImageWithDPI(i, properties.getPageRenderDpi(), ImageType.RGB);
                ByteArrayOutputStream pngOut = new ByteArrayOutputStream();
                ImageIO.write(image, "png", pngOut);

                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(pageNo);
                stripper.setEndPage(pageNo);
                String text = stripper.getText(document);

                Page page = new Page();
                page.setRunId(runId);
                page.setPageNo(pageNo);
                page.setPng(pngOut.toByteArray());
                page.setTextLayer(text == null ? "" : text.strip());
                pageRepository.save(page);
            }
            return pageCount;
        }
    }
}
