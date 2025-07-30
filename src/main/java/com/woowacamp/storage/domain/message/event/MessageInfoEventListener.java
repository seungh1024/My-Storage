package com.woowacamp.storage.domain.message.event;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.woowacamp.storage.domain.message.repository.MessageInfoJpaRepository;
import com.woowacamp.storage.domain.message.util.MessageStatus;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class MessageInfoEventListener {
	private final MessageInfoJpaRepository messageInfoJpaRepository;

	@TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
	public void deleteMessageHandler(MessageInfoEvent event) {
		messageInfoJpaRepository.updateMessageInfoStatus(event.getId(), MessageStatus.SUCCESS);
	}
}
