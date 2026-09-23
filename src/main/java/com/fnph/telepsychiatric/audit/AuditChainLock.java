package com.fnph.telepsychiatric.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "audit_chain_lock")
@Getter
@NoArgsConstructor
public class AuditChainLock {

    @Id
    private Long id;

    @Column(nullable = false)
    private String name;

    public AuditChainLock(Long id, String name) {
        this.id = id;
        this.name = name;
    }
}