package com.campus.studyroom.core.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SourceStateRepository
    extends JpaRepository<SourceStateEntity, SourceStateEntity.Key> {}
