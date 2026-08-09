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

package com.xinshuo.mindflow.rag.core.vector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinshuo.mindflow.core.chunk.VectorChunk;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * pgvector 真实集成测试。
 *
 * <p>测试使用独立 schema，不会读写业务表；需要本地 PostgreSQL（mindflow/123456）
 * 已安装 pgvector 扩展。
 */
@Tag("integration")
class PgVectorStoreServiceIntegrationTest {

    private static final String SCHEMA = "mindflow_vector_it";
    private static final String JDBC_URL = "jdbc:postgresql://127.0.0.1:5432/mindflow";

    private static JdbcTemplate jdbcTemplate;
    private static PgVectorStoreService vectorStoreService;

    @BeforeAll
    static void setUp() {
        JdbcTemplate adminJdbcTemplate = new JdbcTemplate(dataSource(JDBC_URL));
        adminJdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");
        adminJdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA);
        adminJdbcTemplate.execute("DROP TABLE IF EXISTS " + SCHEMA + ".t_knowledge_vector");
        adminJdbcTemplate.execute("""
                CREATE TABLE %s.t_knowledge_vector (
                    id VARCHAR(64) NOT NULL PRIMARY KEY,
                    content TEXT,
                    metadata JSONB,
                    embedding vector(3)
                )
                """.formatted(SCHEMA));

        jdbcTemplate = new JdbcTemplate(dataSource(JDBC_URL + "?currentSchema=" + SCHEMA + ",public"));
        vectorStoreService = new PgVectorStoreService(jdbcTemplate, new ObjectMapper());
    }

    @AfterAll
    static void tearDown() {
        new JdbcTemplate(dataSource(JDBC_URL)).execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    @Test
    void shouldIndexUpdateAndDeleteVectorsInPgvector() {
        VectorChunk first = VectorChunk.builder()
                .chunkId("vector-it-1").content("初始内容").index(0)
                .embedding(new float[]{0.1F, 0.2F, 0.3F}).metadata(java.util.Map.of("block_type", "PARAGRAPH"))
                .build();
        VectorChunk second = VectorChunk.builder()
                .chunkId("vector-it-2").content("第二段内容").index(1)
                .embedding(new float[]{0.3F, 0.2F, 0.1F}).build();

        vectorStoreService.indexDocumentChunks("kb-it", "doc-it", List.of(first, second));

        assertEquals(2, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_knowledge_vector", Integer.class));
        assertEquals("kb-it", jdbcTemplate.queryForObject(
                "SELECT metadata ->> 'collection_name' FROM t_knowledge_vector WHERE id = ?", String.class, "vector-it-1"));
        assertEquals("doc-it", jdbcTemplate.queryForObject(
                "SELECT metadata ->> 'doc_id' FROM t_knowledge_vector WHERE id = ?", String.class, "vector-it-1"));
        assertEquals("[0.1,0.2,0.3]", jdbcTemplate.queryForObject(
                "SELECT embedding::text FROM t_knowledge_vector WHERE id = ?", String.class, "vector-it-1"));

        vectorStoreService.updateChunk("kb-it", "doc-it", VectorChunk.builder()
                .chunkId("vector-it-1").content("更新后的内容").index(8)
                .embedding(new float[]{0.4F, 0.5F, 0.6F}).build());

        assertEquals("更新后的内容", jdbcTemplate.queryForObject(
                "SELECT content FROM t_knowledge_vector WHERE id = ?", String.class, "vector-it-1"));
        assertEquals("8", jdbcTemplate.queryForObject(
                "SELECT metadata ->> 'chunk_index' FROM t_knowledge_vector WHERE id = ?", String.class, "vector-it-1"));

        vectorStoreService.deleteChunkById("kb-it", "vector-it-1");
        assertNull(jdbcTemplate.query(
                "SELECT id FROM t_knowledge_vector WHERE id = ?", rs -> rs.next() ? rs.getString(1) : null, "vector-it-1"));

        vectorStoreService.deleteDocumentVectors("kb-it", "doc-it");
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_knowledge_vector", Integer.class));
    }

    private static DriverManagerDataSource dataSource(String url) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(url);
        dataSource.setUsername("postgres");
        dataSource.setPassword("123456");
        return dataSource;
    }
}
