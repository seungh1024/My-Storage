package com.woowacamp.storage.domain.message.service;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import com.woowacamp.storage.domain.message.dto.FolderMoveMessageDto;
import com.woowacamp.storage.domain.message.dto.FolderSizeMessageDto;
import com.woowacamp.storage.domain.message.routing.RabbitRoute;
import com.woowacamp.storage.domain.message.routing.RabbitRoutesProperties;
import com.woowacamp.storage.domain.message.util.EventType;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SendMessageService {
	private final RabbitTemplate rabbitTemplate;
	private final RabbitRoutesProperties routes;

	public void send(FolderSizeMessageDto message) {
		RabbitRoute r = routes.route(EventType.FOLDER_SIZE);
		rabbitTemplate.convertAndSend(r.exchange(), r.routingKey(), message);
	}

	public void send(FolderMoveMessageDto message) {
		RabbitRoute r = routes.route(EventType.FOLDER_MOVE);
		rabbitTemplate.convertAndSend(r.exchange(), r.routingKey(), message);
	}
}
