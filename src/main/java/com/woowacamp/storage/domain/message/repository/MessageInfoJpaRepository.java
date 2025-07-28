package com.woowacamp.storage.domain.message.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.woowacamp.storage.domain.message.entity.MessageInfo;
import com.woowacamp.storage.domain.message.entity.MessageInfoId;
import com.woowacamp.storage.domain.message.util.MessageStatus;

public interface MessageInfoJpaRepository extends JpaRepository<MessageInfo, Long> {

	@Modifying
	@Query("""
			UPDATE MessageInfo m
			SET m.status = :status
			WHERE m.id = :id
		""")
	int updateMessageInfoSuccess(@Param("id") MessageInfoId id, @Param("status")MessageStatus status);
}
