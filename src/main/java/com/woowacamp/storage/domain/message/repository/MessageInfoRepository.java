package com.woowacamp.storage.domain.message.repository;

import java.util.List;

import org.springframework.stereotype.Component;

import com.woowacamp.storage.domain.message.entity.MessageInfo;
import com.woowacamp.storage.domain.message.util.MessageStatus;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class MessageInfoRepository {
	private final MessageInfoJpaRepository messageInfoJpaRepository;

	public List<MessageInfo> findPendingMessageWithSize(Long id, int size, int maxRetry) {
		if (id == null) {
			return messageInfoJpaRepository.findPendingMessageWithSize(MessageStatus.PENDING, maxRetry, size);
		}
		return messageInfoJpaRepository.findPendingMessageWithSize(id, MessageStatus.PENDING, maxRetry, size);
	}

	public List<MessageInfo> findMaxRetryMessageWithSize(Long id, int size, int maxRetry) {
		if (id == null) {
			return messageInfoJpaRepository.findMaxRetryMessageWithSize(MessageStatus.PENDING, maxRetry, size);
		}

		return messageInfoJpaRepository.findMaxRetryMessageWithSize(id, MessageStatus.PENDING, maxRetry, size);
	}

	public List<MessageInfo> findSuccessMessageWithSize(Long id, int size) {
		if (id == null) {
			return messageInfoJpaRepository.findSuccessMessageWithSize(MessageStatus.SUCCESS, size);
		}
		return messageInfoJpaRepository.findSuccessMessageWithSize(id, MessageStatus.SUCCESS, size);
	}
}
