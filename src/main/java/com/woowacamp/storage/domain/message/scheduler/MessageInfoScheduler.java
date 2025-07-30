package com.woowacamp.storage.domain.message.scheduler;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.woowacamp.storage.domain.file.util.StringFormat;
import com.woowacamp.storage.domain.folder.dto.message.FolderSizeMessageDto;
import com.woowacamp.storage.domain.folder.utils.QueryExecuteTemplate;
import com.woowacamp.storage.domain.message.entity.MessageInfo;
import com.woowacamp.storage.domain.message.repository.MessageInfoJpaRepository;
import com.woowacamp.storage.domain.message.repository.MessageInfoRepository;
import com.woowacamp.storage.domain.message.service.SendMessageService;
import com.woowacamp.storage.domain.message.util.JsonSerializer;
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
				messageInfoJpaRepository.updateMessageInfoStatus(message.getId(), MessageStatus.SUCCESS);
				sendMessage(message);
			}));
	}

	private void sendMessage(MessageInfo message) {
		FolderSizeMessageDto folderSizeMessageDto = jsonSerializer.deserialize(message.getPayload(),
			FolderSizeMessageDto.class);
		try {
			sendMessageService.sendMessage(folderSizeMessageDto);
		} catch (Exception e) {
			messageInfoJpaRepository.updateRetryCount(message.getId());
			log.error(StringFormat.format("[SEND MESSAGE SCHEDULER ERROR] ID = {}", message.getId()), e);
		}
	}

	@Scheduled(fixedDelay = deleteMessageDelay)
	public void deleteSuccessMessage() {
		int result = deleteSize;
		while (result >= deleteSize) {
			result = messageInfoJpaRepository.deleteMessagesWithSize(MessageStatus.SUCCESS.name(), deleteSize);
		}
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

