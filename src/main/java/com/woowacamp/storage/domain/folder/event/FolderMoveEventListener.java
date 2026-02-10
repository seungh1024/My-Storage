package com.woowacamp.storage.domain.folder.event;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.woowacamp.storage.domain.message.entity.MessageInfo;
import com.woowacamp.storage.domain.message.repository.MessageInfoJpaRepository;
import com.woowacamp.storage.domain.message.service.SendMessageService;
import com.woowacamp.storage.domain.message.util.JsonSerializer;
import com.woowacamp.storage.domain.message.util.MessageStatus;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class FolderMoveEventListener {
	private final MessageInfoJpaRepository messageInfoJpaRepository;
	private final SendMessageService sendMessageService;
	private final JsonSerializer jsonSerializer;

	/**
	 * DB에 outbox 이벤트 기록
	 * @param folderSizeEvent
	 */
	@TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
	public void recordMessageHandler(FolderSizeEvent folderSizeEvent) {
		MessageInfo messageInfo = folderSizeEvent.toEntity(jsonSerializer.serialize(folderSizeEvent.message()));
		MessageInfo savedMessageInfo = messageInfoJpaRepository.save(messageInfo);
		folderSizeEvent.setId(savedMessageInfo.getId());
	}

	@TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
	public void recordMessageHandler(FolderMoveEvent folderMoveEvent) {
		MessageInfo messageInfo = folderMoveEvent.toEntity(jsonSerializer.serialize(folderMoveEvent.message()));
		MessageInfo savedMessageInfo = messageInfoJpaRepository.save(messageInfo);
		folderMoveEvent.setId(savedMessageInfo.getId());
	}

	/**
	 * mq에 용량 처리 메세지 전송
	 * net I/O처리 시간이 길기 때문에 비동기 처리
	 * @param folderSizeEvent
	 */
	@Async(value = "sendMessageExecutor")
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void sendMessageHandler(FolderSizeEvent folderSizeEvent) {
		sendMessageService.send(folderSizeEvent.message());
		updateMessageStatusSent(folderSizeEvent.getId());
	}

	@Async(value = "sendMessageExecutor")
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void sendMessageHandler(FolderMoveEvent folderMoveEvent) {
		sendMessageService.send(folderMoveEvent.message());
		updateMessageStatusSent(folderMoveEvent.getId());
	}

	private void updateMessageStatusSent(Long id) {
		messageInfoJpaRepository.updateMessageInfoStatus(id, MessageStatus.SENT);
	}
}
