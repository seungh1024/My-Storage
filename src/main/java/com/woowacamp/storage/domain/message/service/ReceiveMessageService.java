package com.woowacamp.storage.domain.message.service;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.woowacamp.storage.domain.folder.dto.message.FolderSizeMessageDto;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class ReceiveMessageService {

	@RabbitListener(queues = "${spring.rabbitmq.folder-size-queue}")
	public void handleFolderSizeEvent(FolderSizeMessageDto message) {
		log.info("uuid = {}, id = {}, event type = {}, size = {}",message.uuid(),message.folderMetadataId(), message.eventType(), message.size());
	}
}
