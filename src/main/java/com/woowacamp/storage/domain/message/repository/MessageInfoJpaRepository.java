package com.woowacamp.storage.domain.message.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.woowacamp.storage.domain.message.entity.MessageInfo;
import com.woowacamp.storage.domain.message.util.MessageStatus;

public interface MessageInfoJpaRepository extends JpaRepository<MessageInfo, Long> {

	@Modifying
	@Query("""
			UPDATE MessageInfo m
			SET m.status = :status
			WHERE m.id = :id
		""")
	int updateMessageInfoStatus(@Param("id") Long id, @Param("status") MessageStatus status);

	/**
	 * 첫 번째 조회 쿼리
	 */
	@Query("""
			SELECT m
			FROM MessageInfo m
			WHERE m.status = :status AND m.retryCount <= :retryCount
			ORDER BY m.id
			LIMIT :size
		""")
	List<MessageInfo> findPendingMessageWithSize(@Param("status") MessageStatus status,
		@Param("retryCount") int retryCount, @Param("size") int size);

	/**
	 * 두 번째 이상 페이징 처리 쿼리
	 */
	@Query("""
			SELECT m
			FROM MessageInfo m
			WHERE m.status = :status AND m.id > :id AND m.retryCount <= :retryCount
			ORDER BY m.id
			LIMIT :size
		""")
	List<MessageInfo> findPendingMessageWithSize(@Param("id") Long id, @Param("status") MessageStatus status,
		@Param("retryCount") int retryCount, @Param("size") int size);

	@Modifying
	@Query("""
			UPDATE MessageInfo m
			SET m.retryCount = m.retryCount+1
			WHERE m.id = :id
		""")
	void updateRetryCount(@Param("id") Long id);

	@Transactional
	@Modifying
	@Query(value = """
			DELETE FROM message_info
		   	WHERE status = :status
		   	ORDER BY message_info_id
		   	LIMIT :size
		""", nativeQuery = true)
	int deleteMessagesWithSize(@Param("status") String status, @Param("size") int size);

	@Query("""
			SELECT m
			FROM MessageInfo m
			WHERE m.status = :status AND m.retryCount > :retryCount
			ORDER BY m.id
			LIMIT :size
		""")
	List<MessageInfo> findMaxRetryMessageWithSize(@Param("status") MessageStatus status,
		@Param("retryCount") int retryCount, @Param("size") int size);

	@Query("""
			SELECT m
			FROM MessageInfo m
			WHERE m.status = :status AND m.id > :id AND m.retryCount > :retryCount
			ORDER BY m.id
			LIMIT :size
		""")
	List<MessageInfo> findMaxRetryMessageWithSize(@Param("id") Long id, @Param("status") MessageStatus status,
		@Param("retryCount") int retryCount, @Param("size") int size);
}
