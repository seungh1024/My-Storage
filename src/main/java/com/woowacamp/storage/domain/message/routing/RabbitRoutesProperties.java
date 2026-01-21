package com.woowacamp.storage.domain.message.routing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.woowacamp.storage.domain.message.util.EventType;

@Component
public class RabbitRoutesProperties {

	private final String folderExchange;
	private final String folderSizeKey;
	private final String folderMoveKey;

	public RabbitRoutesProperties(
		@Value("${spring.rabbitmq.folder.exchange}") String folderExchange,
		@Value("${spring.rabbitmq.folder.size.key}") String folderSizeKey,
		@Value("${spring.rabbitmq.folder.move.key}") String folderMoveKey) {
		this.folderExchange = folderExchange;
		this.folderSizeKey = folderSizeKey;
		this.folderMoveKey = folderMoveKey;
	}

	public RabbitRoute route(EventType type) {
		return switch (type) {
			case FOLDER_SIZE -> new RabbitRoute(folderExchange, folderSizeKey);
			case FOLDER_MOVE -> new RabbitRoute(folderExchange, folderMoveKey);
		};
	}
}
