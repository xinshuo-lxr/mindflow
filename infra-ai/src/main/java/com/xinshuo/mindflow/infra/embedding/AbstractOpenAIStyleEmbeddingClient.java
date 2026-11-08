/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.xinshuo.mindflow.infra.embedding;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.xinshuo.mindflow.framework.exception.RemoteException;
import com.xinshuo.mindflow.infra.config.AIModelProperties;
import com.xinshuo.mindflow.infra.enums.ModelCapability;
import com.xinshuo.mindflow.infra.http.HttpMediaTypes;
import com.xinshuo.mindflow.infra.http.HttpResponseHelper;
import com.xinshuo.mindflow.infra.http.ModelClientErrorType;
import com.xinshuo.mindflow.infra.http.ModelClientException;
import com.xinshuo.mindflow.infra.http.ModelUrlResolver;
import com.xinshuo.mindflow.infra.model.ModelTarget;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI 兼容风格 Embedding 客户端基类
 */
@Slf4j
public abstract class AbstractOpenAIStyleEmbeddingClient implements EmbeddingClient {

    @Autowired
    private OkHttpClient syncHttpClient;
    protected Gson gson = new Gson();

    @Override
    public List<Float> embed(String text, ModelTarget target) {
        List<List<Float>> batch = embedBatch(List.of(text), target);
        if (batch.isEmpty()) throw new RemoteException("Embedding 返回空结果");
        return batch.get(0);
    }

    @Override
    public List<List<Float>> embedBatch(List<String> texts, ModelTarget target) {
        AIModelProperties.ProviderConfig provider = HttpResponseHelper.requireProvider(target, provider());
        HttpResponseHelper.requireApiKey(provider, provider());

        JsonObject body = new JsonObject();
        body.addProperty("model", HttpResponseHelper.requireModel(target, provider()));
        JsonArray inputs = new JsonArray(texts.size());
        for (String t : texts) inputs.add(t);
        body.add("input", inputs);

        Request request = new Request.Builder()
                .url(ModelUrlResolver.resolveUrl(provider, target.candidate(), ModelCapability.EMBEDDING))
                .addHeader("Authorization", "Bearer " + provider.getApiKey())
                .post(RequestBody.create(body.toString(), HttpMediaTypes.JSON))
                .build();

        try (Response response = syncHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String respBody = HttpResponseHelper.readBody(response.body());
                log.warn("{} Embedding 请求失败: status={}, body={}", provider(), response.code(), respBody);
                throw new ModelClientException(provider() + " Embedding 请求失败: HTTP " + response.code(),
                        ModelClientErrorType.fromHttpStatus(response.code()), response.code());
            }
            JsonObject json = HttpResponseHelper.parseJson(response.body(), provider());
            JsonArray data = json.getAsJsonArray("data");
            if (data == null || data.isEmpty()) throw new RemoteException("Embedding 响应缺少 data 字段");

            List<List<Float>> results = new ArrayList<>(texts.size());
            for (int i = 0; i < data.size(); i++) {
                JsonArray emb = data.get(i).getAsJsonObject().getAsJsonArray("embedding");
                List<Float> vec = new ArrayList<>(emb.size());
                for (int j = 0; j < emb.size(); j++) vec.add(emb.get(j).getAsFloat());
                results.add(vec);
            }
            return results;
        } catch (ModelClientException | RemoteException e) {
            throw e;
        } catch (Exception e) {
            throw new RemoteException("Embedding 请求失败: " + e.getMessage());
        }
    }
}
