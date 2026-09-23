CREATE TABLE audit_chain_lock (
    id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB;

INSERT INTO audit_chain_lock (id, name)
VALUES (1, 'GLOBAL_AUDIT_CHAIN');