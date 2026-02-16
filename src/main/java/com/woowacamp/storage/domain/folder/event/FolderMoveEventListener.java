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

	@TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
	public void recordMessageHandler(FolderMoveEvent folderMoveEvent) {
		MessageInfo messageInfo = folderMoveEvent.toEntity(jsonSerializer.serialize(folderMoveEvent.message()));
		MessageInfo savedMessageInfo = messageInfoJpaRepository.save(messageInfo);
		folderMoveEvent.setId(savedMessageInfo.getId());
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
