package com.woowacamp.storage.domain.message.service;

import java.util.List;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.woowacamp.storage.domain.file.util.StringFormat;
import com.woowacamp.storage.domain.folder.dto.message.FolderSizeMessageDto;
import com.woowacamp.storage.domain.folder.service.FolderService;
import com.woowacamp.storage.global.error.CustomException;
import com.woowacamp.storage.global.error.ErrorCode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReceiveMessageService {
	private final FolderService folderService;

	@Value("${constant.retryCnt}")
	private int retryCnt;

	@RabbitListener(queues = "${spring.rabbitmq.folder-size-queue}", containerFactory = "folderSizeFactory")
	public void handleFolderSizeEvent(List<FolderSizeMessageDto> messages) {
		messages.stream().forEach(message -> {
			// log.info("id = {}, FOLDER METADATA ID = {}, event type = {}, size = {}", message.id(),
			// 	message.folderMetadataId(), message.eventType(), message.size());

			// 스케줄러가 처리되지 않은 메세지는 재발행을 할 것이기 때문에 error가 발생해도 ack는 진행한다.
			try {
				boolean result = tryUpdateFolderSize(message);

				if (!result) {
					throw ErrorCode.CANNOT_UPDATE_SIZE.baseException(
						StringFormat.format("3회 재시도 실패, ID = {},  FOLDER METADATA ID : {}", message.id(),
							message.folderMetadataId()));
				}
			} catch (CustomException e) {
				log.error(StringFormat.format(
					"[ReceiveMessageService Size Event Error] Error Message : {}, DebugMessage : {}", e.getMessage(),
					e.getDebugMessage()));
			} catch (Exception e) {
				log.error(String.format("[Unhandled Exception] FolderMetadataId: {}", message.folderMetadataId()), e);
			}
		});

	}

	private boolean tryUpdateFolderSize(FolderSizeMessageDto message) {
		int cnt = retryCnt;
		while (cnt-- > 0) {
			int result = folderService.updateFolderSize(message.id(), message.folderMetadataId(), message.size());
			if (result == 1) {
				return true;
			}
		}
		return false;
	}
}
