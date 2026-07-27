package com.xinshuo.mindflow.framework.mq;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MessageWrapper<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String keys;

    private T body;

    @Builder.Default
    private String uuid = UUID.randomUUID().toString();

    @Builder.Default
    private Long timestamp = System.currentTimeMillis();
}
