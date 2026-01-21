package com.woowacamp.storage.domain.folder.event;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.woowacamp.storage.domain.message.entity.MessageInfo;
import com.woowacamp.storage.domain.message.repository.MessageInfoJpaRepository;
import com.woowacamp.storage.domain.message.service.SendMessageService;
import com.woowacamp.storage.domain.message.util.EventType;
import com.woowacamp.storage.domain.message.util.JsonSerializer;

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
		MessageInfo messageInfo = folderSizeEvent.toEntity(jsonSerializer.serialize(folderSizeEvent));
		MessageInfo savedMessageInfo = messageInfoJpaRepository.save(messageInfo);
		folderSizeEvent.setId(savedMessageInfo.getId());
	}

	/**
	 * mq에 용량 처리 메세지 전송
	 * net I/O처리 시간이 길기 때문에 비동기 처리
	 * @param folderSizeEvent
	 */
	@Async(value = "SEND_MESSAGE_EXECUTOR")
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void sendMessageHandler(FolderSizeEvent folderSizeEvent) {
		sendMessageService.send(folderSizeEvent.message());
	}

	@Async(value = "SEND_MESSAGE_EXECUTOR")
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void sendMessageHandler(FolderMoveEvent folderMoveEvent) {
		sendMessageService.send(folderMoveEvent.message());
	}
}
