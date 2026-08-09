DROP TABLE IF EXISTS t_knowledge_vector;
DROP TABLE IF EXISTS t_knowledge_chunk;

CREATE TABLE t_knowledge_chunk (
    id              VARCHAR(20)  NOT NULL PRIMARY KEY,
    kb_id           VARCHAR(20),
    doc_id          VARCHAR(20),
    chunk_index     INT,
    content         TEXT,
    content_hash    VARCHAR(64),
    char_count      INT,
    token_count     INT,
    enabled         SMALLINT     DEFAULT 1,
    created_by      VARCHAR(64),
    updated_by      VARCHAR(64),
    create_time     TIMESTAMP  DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP  DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT     DEFAULT 0
);

CREATE TABLE t_knowledge_vector (
    id          VARCHAR(64) NOT NULL PRIMARY KEY,
    content     TEXT,
    metadata    JSONB,
    embedding   vector(8)
);
