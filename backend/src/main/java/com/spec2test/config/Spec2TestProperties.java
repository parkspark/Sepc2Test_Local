package com.spec2test.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "spec2test.llm")
public class Spec2TestProperties {

    private String provider = "ollama";
    private String textModel = "qwen3-coder:30b";
    private String visionModel = "qwen2.5vl:32b";
    private int numCtx = 65536;
    private int sectionsNumCtx = 131072;
    private double temperature = 0.2;
    private Duration callTimeout = Duration.ofMinutes(30);
    private Duration keepAlive = Duration.ofMinutes(10);
    private int maxValidateRetries = 3;
    private int pageRenderDpi = 150;
    private int ragTopK = 6;

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getTextModel() {
        return textModel;
    }

    public void setTextModel(String textModel) {
        this.textModel = textModel;
    }

    public String getVisionModel() {
        return visionModel;
    }

    public void setVisionModel(String visionModel) {
        this.visionModel = visionModel;
    }

    public int getNumCtx() {
        return numCtx;
    }

    public void setNumCtx(int numCtx) {
        this.numCtx = numCtx;
    }

    public int getSectionsNumCtx() {
        return sectionsNumCtx;
    }

    public void setSectionsNumCtx(int sectionsNumCtx) {
        this.sectionsNumCtx = sectionsNumCtx;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public Duration getCallTimeout() {
        return callTimeout;
    }

    public void setCallTimeout(Duration callTimeout) {
        this.callTimeout = callTimeout;
    }

    public Duration getKeepAlive() {
        return keepAlive;
    }

    public void setKeepAlive(Duration keepAlive) {
        this.keepAlive = keepAlive;
    }

    public int getMaxValidateRetries() {
        return maxValidateRetries;
    }

    public void setMaxValidateRetries(int maxValidateRetries) {
        this.maxValidateRetries = maxValidateRetries;
    }

    public int getPageRenderDpi() {
        return pageRenderDpi;
    }

    public void setPageRenderDpi(int pageRenderDpi) {
        this.pageRenderDpi = pageRenderDpi;
    }

    public int getRagTopK() {
        return ragTopK;
    }

    public void setRagTopK(int ragTopK) {
        this.ragTopK = ragTopK;
    }
}
