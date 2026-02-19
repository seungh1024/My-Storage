package com.woowacamp.storage.domain.message.routing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.woowacamp.storage.domain.message.util.EventType;

@Component
public class RabbitRoutesProperties {

	private final String folderExchange;
	private final String folderMoveKey;

	public RabbitRoutesProperties(
		@Value("${spring.rabbitmq.folder.exchange}") String folderExchange,
		@Value("${spring.rabbitmq.folder.move.key}") String folderMoveKey) {
		this.folderExchange = folderExchange;
		this.folderMoveKey = folderMoveKey;
	}

	public RabbitRoute route(EventType type) {
		if (type != EventType.FOLDER_MOVE) {
			throw new IllegalArgumentException("Unsupported event type for Rabbit route: " + type);
		}
		return new RabbitRoute(folderExchange, folderMoveKey);
	}
}
