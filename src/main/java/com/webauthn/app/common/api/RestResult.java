package com.webauthn.app.common.api;

import ch.qos.logback.core.joran.spi.ActionException;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Schema(description = "交易結果DTO")
@Data
public class RestResult<T> implements java.io.Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "交易結果代碼", example = "200")
    private String status;
    @Schema(description = "錯誤訊息")
    private String message;
    @Schema(description = "交易結果DTO")
    private T data;
    @Schema(description = "交易失敗結果DTO")
    private Object errorData;
    @Schema(description = "交易時間", example = "2025-04-25T11:33:09.0967005")
    private LocalDateTime time;

    public RestResult() {
    }

    public RestResult(RestStatus status, String message) {
        this.status = status.CODE;
        this.message = message;
    }

    public RestResult(RestStatus status, String message, T data) {
        this.status = status.CODE;
        this.message = message;
        this.data = data;
    }

    public RestResult(RestStatus status, T data) {
        this.status = status.CODE;
        this.message = status.MESSAGE;
        this.data = data;
        this.time = LocalDateTime.now();
    }

    public RestResult(T data) {
        this.status = RestStatus.SUCCESS.CODE;
        this.message = RestStatus.SUCCESS.MESSAGE;
        this.time = LocalDateTime.now();
        this.data = data;
    }

    public RestResult(RestStatus status) {
        this.status = status.CODE;
        this.message = status.MESSAGE;
        this.time = LocalDateTime.now();
    }

    public RestResult(String status, String message) {
        this.status = status;
        this.message = message;
    }

    public RestResult(String status, String message, Object errorData) {
        this.status = status;
        this.message = message;
        this.errorData = errorData;
    }

    @Schema(hidden = true)
    public boolean isSuccess() {
        return RestStatus.SUCCESS.CODE.equals(status);
    }

}
