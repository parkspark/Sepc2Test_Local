package com.spec2test.pipeline;

/** 사용자가 Stop을 요청해 다음 안전 지점에서 파이프라인을 중단할 때 던진다. */
public class PipelineCancelledException extends RuntimeException {

    public PipelineCancelledException() {
        super("Pipeline cancelled by user");
    }
}
