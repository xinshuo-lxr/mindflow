package com.xinshuo.mindflow.infra.http;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * HTTP 响应处理工具类
 * 集中管理 OkHttp 响应读取、JSON 解析等公共逻辑
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class HttpResponseHelper {

    private static final Gson GSON = new Gson();

    /**
     * 读取响应体原始字符串
     */
    public static String readBody(ResponseBody body) throws IOException {
        if (body == null) {
            return "";
        }
        return new String(body.bytes(), StandardCharsets.UTF_8);
    }

    /**
     * 将响应体解析为 JsonObject
     *
     * @param body  OkHttp 响应体
     * @param label 提供商标签，用于异常消息
     * @return 解析后的 JsonObject
     */
    public static JsonObject parseJson(ResponseBody body, String label) throws IOException {
        if (body == null) {
            throw new ModelClientException(label + " 响应为空", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        String content = body.string();
        return GSON.fromJson(content, JsonObject.class);
    }
}
