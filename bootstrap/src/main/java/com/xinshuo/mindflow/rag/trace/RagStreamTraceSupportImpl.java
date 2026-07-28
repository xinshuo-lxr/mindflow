package com.xinshuo.mindflow.rag.trace;

import com.xinshuo.mindflow.framework.trace.RagStreamTraceSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 跨线程 stream trace 实现：解决 @RagTraceNode AOP 在 stream 场景
 * 只测到 runAsync 提交的问题
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagStreamTraceSupportImpl implements RagStreamTraceSupport {
    @Override
    public StreamSpan beginStreamNode(String name, String type) {
        return null;
    }
}
