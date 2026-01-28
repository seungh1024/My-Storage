package com.woowacamp.storage.domain.message.service;

import java.util.List;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.woowacamp.storage.domain.file.util.StringFormat;
import com.woowacamp.storage.domain.folder.service.FolderMoveProcessor;
import com.woowacamp.storage.domain.folder.service.FolderService;
import com.woowacamp.storage.domain.message.dto.FolderMoveMessageDto;
import com.woowacamp.storage.domain.message.dto.FolderSizeMessageDto;
import com.woowacamp.storage.domain.message.event.MessageInfoEvent;
import com.woowacamp.storage.domain.message.repository.MessageInfoJpaRepository;
import com.woowacamp.storage.domain.message.util.MessageStatus;
import com.woowacamp.storage.global.error.ErrorCode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReceiveMessageService {

	private final FolderService folderService;
	private final FolderMoveProcessor folderMoveProcessor;
	private final MessageInfoJpaRepository messageInfoJpaRepository;
	private final ApplicationEventPublisher publisher;


	@RabbitListener(queues = "${spring.rabbitmq.folder.size.queue}", containerFactory = "folderSizeFactory")
	public void handleFolderSizeEvent(FolderSizeMessageDto message) {
		// 처리 실패는 예외로 올려서 retry/DLQ로 전송
		int updated = folderService.updateFolderSize(message.id(), message.folderMetadataId(), message.size());

		if (updated != 1) {
			throw ErrorCode.CANNOT_UPDATE_SIZE.baseException(
				StringFormat.format("Update failed. outboxId={}, folderId={}", message.id(),
					message.folderMetadataId()));
		}
		log.info("[ReceiveMessageService] size update success, folder id: {}, size: {}", message.folderMetadataId(),
			message.size());
	}

	/**
	 * 폴더 이동 메시지 처리
	 * 트랜잭션 없음
	 */
	@RabbitListener(queues = "${spring.rabbitmq.folder.move.queue}", containerFactory = "folderMoveFactory")
	public void handleFolderMoveEvent(FolderMoveMessageDto message) {
		log.info("[ReceiveMessageService] Received FolderMoveMessage. messageId={}, folderId={}",
			message.id(), message.parentFolderMetadataId());

		// 1. 멱등성 체크
		boolean alreadyProcessed = messageInfoJpaRepository.existsByIdAndStatusIn(
			message.id(), List.of(MessageStatus.PENDING, MessageStatus.SENT));

		if (!alreadyProcessed) {
			log.info("[ReceiveMessageService] Message already processed. messageId={}", message.id());
			return;
		}

		try {
			// 2. Outbox 완료 처리 - 별도 트랜잭션
			publisher.publishEvent(new MessageInfoEvent(message.id()));

			// 3. 배치 작업 수행 - 트랜잭션 없음
			folderMoveProcessor.processMove(message.parentFolderMetadataId());

			log.info("[ReceiveMessageService] Completed. messageId={}, folderId={}",
				message.id(), message.parentFolderMetadataId());

		} catch (Exception e) {
			log.error("[ReceiveMessageService] Failed. messageId={}, folderId={}",
				message.id(), message.parentFolderMetadataId(), e);
			throw e;
		}
	}

}