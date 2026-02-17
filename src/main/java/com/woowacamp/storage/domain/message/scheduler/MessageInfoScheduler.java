package com.woowacamp.storage.domain.message.scheduler;

import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.woowacamp.storage.domain.file.util.StringFormat;
import com.woowacamp.storage.domain.folder.utils.QueryExecuteTemplate;
import com.woowacamp.storage.domain.message.dto.FolderMoveMessageDto;
import com.woowacamp.storage.domain.message.dto.OutboxMessage;
import com.woowacamp.storage.domain.message.entity.MessageInfo;
import com.woowacamp.storage.domain.message.repository.MessageInfoJpaRepository;
import com.woowacamp.storage.domain.message.repository.MessageInfoRepository;
import com.woowacamp.storage.domain.message.service.SendMessageService;
import com.woowacamp.storage.domain.message.util.JsonSerializer;
import com.woowacamp.storage.domain.message.util.EventType;
import com.woowacamp.storage.domain.message.util.MessageStatus;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class MessageInfoScheduler {
	private final MessageInfoRepository messageInfoRepository;
	private final MessageInfoJpaRepository messageInfoJpaRepository;
	private final SendMessageService sendMessageService;
	private final JsonSerializer jsonSerializer;

	@Value("${constant.batchSize}")
	private int limit;
	@Value("${constant.maxRetry}")
	private int maxRetry;
	@Value("${constant.deleteSize}")
	private int deleteSize;

	private final static int retryMessageDelay = 5000;

	private final static int deleteMessageDelay = 1000 * 60 * 60;

	@Transactional
	@Scheduled(fixedDelay = retryMessageDelay)
	public void retryMessage() {
		QueryExecuteTemplate.<MessageInfo>selectFilesAndExecuteWithCursor(limit,
			findMessage -> messageInfoRepository.findPendingMessageWithSize(
				findMessage == null ? null : findMessage.getId(), limit, maxRetry),
			findMessageList -> findMessageList.stream().forEach(message -> {
				sendMessage(message);
			}));
	}

	private void sendMessage(MessageInfo message) {
		OutboxMessage outboxMessage = buildOutboxMessage(message);
		if (outboxMessage == null) {
			log.warn("[SEND MESSAGE SCHEDULER] skip message. id={}, eventType={}", message.getId(),
				message.getEventType());
			messageInfoJpaRepository.updateMessageInfoStatus(message.getId(), MessageStatus.FAILED);
			return;
		}
		try {
			sendMessageService.send(outboxMessage);
		} catch (Exception e) {
			messageInfoJpaRepository.updateRetryCount(message.getId());
			log.error(StringFormat.format("[SEND MESSAGE SCHEDULER ERROR] ID = {}", message.getId()), e);
		}
	}

	private OutboxMessage buildOutboxMessage(MessageInfo message) {
		EventType type = message.getEventType();
		if (type == null) {
			return null;
		}
		if (type != EventType.FOLDER_MOVE) {
			return null;
		}
		return buildFolderMoveMessage(message);
	}

	private FolderMoveMessageDto buildFolderMoveMessage(MessageInfo message) {
		FolderMoveMessageDto dto = jsonSerializer.deserialize(message.getPayload(), FolderMoveMessageDto.class);
		Long parentId = dto.parentFolderMetadataId();
		if (parentId == null) {
			LegacyFolderMovePayload legacy = jsonSerializer.deserialize(message.getPayload(),
				LegacyFolderMovePayload.class);
			parentId = legacy.parentFolderId();
		}
		if (parentId == null) {
			return null;
		}
		return new FolderMoveMessageDto(message.getId(), parentId, EventType.FOLDER_MOVE);
	}

	private record LegacyFolderMovePayload(Long id, Long parentFolderId) {
	}

	@Scheduled(fixedDelay = deleteMessageDelay)
	public void deleteSuccessMessage() {
		QueryExecuteTemplate.<MessageInfo>selectFilesAndExecuteWithCursor(deleteSize,
			messageList -> messageInfoRepository.findSuccessMessageWithSize(
				messageList == null ? null : messageList.getId(), deleteSize),
			messageList -> messageInfoJpaRepository.deleteMessagesInId(messageList.stream()
				.map(MessageInfo::getId)
				.collect(Collectors.toList())));
	}

	@Transactional
	@Scheduled(fixedDelay = retryMessageDelay)
	public void failMessageHandler() {
		QueryExecuteTemplate.<MessageInfo>selectFilesAndExecuteWithCursor(limit,
			findMessage -> messageInfoRepository.findMaxRetryMessageWithSize(
				findMessage == null ? null : findMessage.getId(), limit, maxRetry),
			findMessageList -> findMessageList.stream().forEach(message -> {
				messageInfoJpaRepository.updateMessageInfoStatus(message.getId(), MessageStatus.FAILED);
			}));
	}
}
