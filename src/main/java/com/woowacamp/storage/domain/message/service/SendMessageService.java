package com.woowacamp.storage.domain.message.service;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class SendMessageService {
	private final RabbitTemplate rabbitTemplate;
	private final String exchangeName;
	private final String routingKey;

	public SendMessageService(RabbitTemplate rabbitTemplate,
		@Value("${spring.rabbitmq.folder-size-exchange}") String exchangeName,
		@Value("${spring.rabbitmq.folder-size-key}") String routingKey) {
		this.rabbitTemplate = rabbitTemplate;
		this.exchangeName = exchangeName;
		this.routingKey = routingKey;
	}

	public void sendMessage(String message) {
		rabbitTemplate.convertAndSend(exchangeName, routingKey, message);
	}

}
