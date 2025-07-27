package com.woowacamp.storage.domain.message.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.woowacamp.storage.domain.message.entity.MessageInfo;

public interface MessageInfoJpaRepository extends JpaRepository<MessageInfo, Long> {
}
