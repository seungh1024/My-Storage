package com.woowacamp.storage.domain.folder.event;

import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.woowacamp.storage.domain.shredlink.service.SharedLinkService;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class FolderMoveEventListener {
	private final SharedLinkService sharingService;

	@EventListener
	public void updateSubSharingStatus(FolderMoveEvent moveEvent) {
		sharingService.updateFolderSharingStatus(moveEvent.getSourceFolder().getId(),
			moveEvent.getTargetFolder().getPermissionType(), moveEvent.getTargetFolder().getSharingExpiredAt());
	}

	/**
	 * mq에 용량 처리 메세지 전송
	 * net I/O처리 시간이 길기 때문에 비동기 처리
	 * @param folderMoveEvent
	 */
	@Async(value = "SEND_MESSAGE_EXECUTOR")
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void sendMessageHandler(FolderMoveEvent folderMoveEvent) {

	}
}
