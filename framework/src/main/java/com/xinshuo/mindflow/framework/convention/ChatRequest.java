package com.xinshuo.mindflow.framework.convention;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Builder.Default;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatRequest {

    @Default
    private List<ChatMessage> messages = new ArrayList<>();

    private Double temperature;

    private Double topP;

    private Integer topK;

    private Integer maxTokens;

    private Boolean thinking;

    private Boolean enableTools;
}
