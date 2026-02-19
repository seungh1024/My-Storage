package com.woowacamp.storage.global.config;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.test.util.ReflectionTestUtils;

import com.woowacamp.storage.domain.message.repository.MessageInfoJpaRepository;
import com.woowacamp.storage.domain.message.util.MessageStatus;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class RabbitMqConfigTest {

	@Mock
	private MessageInfoJpaRepository messageInfoJpaRepository;

	@Mock
	private ConnectionFactory connectionFactory;

	@Test
	@DisplayName("Queue/Binding/Exchange 설정이 프로퍼티 기반으로 생성된다")
	void buildQueuesAndBindings() {
		RabbitMqConfig config = newConfig();

		TopicExchange exchange = config.folderExchange();
		assertEquals("folder.exchange", exchange.getName());

		Queue moveQueue = config.folderMoveQueue();
		assertEquals("folder.move.queue", moveQueue.getName());
		assertEquals(1234, moveQueue.getArguments().get("x-message-ttl"));
		assertEquals("folder.move.dlx.exchange", moveQueue.getArguments().get("x-dead-letter-exchange"));
		assertEquals("folder.move.dlx.key", moveQueue.getArguments().get("x-dead-letter-routing-key"));

		Binding moveBinding = config.folderMoveBinding(exchange, moveQueue);
		assertEquals("folder.move.queue", moveBinding.getDestination());
		assertEquals("folder.exchange", moveBinding.getExchange());
		assertEquals("folder.move.key", moveBinding.getRoutingKey());

		DirectExchange moveDlx = config.folderMoveDlxExchange();
		assertEquals("folder.move.dlx.exchange", moveDlx.getName());
		Queue moveDlq = config.folderMoveDlq();
		assertEquals("folder.move.dlx.queue", moveDlq.getName());
		Binding moveDlqBinding = config.folderMoveDlqBinding(moveDlq, moveDlx);
		assertEquals("folder.move.dlx.key", moveDlqBinding.getRoutingKey());
	}

	@Test
	@DisplayName("RabbitTemplate 콜백에서 outbox 상태를 갱신한다")
	void rabbitTemplate_callbacksUpdateOutbox() {
		RabbitMqConfig config = newConfig();
		MessageConverter converter = config.messageConverter();
		assertTrue(converter instanceof Jackson2JsonMessageConverter);

		RabbitTemplate template = config.rabbitTemplate(connectionFactory, converter);
		assertSame(converter, template.getMessageConverter());

		CorrelationData correlationData = new CorrelationData("10");
		RabbitTemplate.ConfirmCallback confirmCallback = getConfirmCallback(template);
		assertNotNull(confirmCallback);

		confirmCallback.confirm(correlationData, true, null);
		then(messageInfoJpaRepository).should().markSent(10L, MessageStatus.SENT);

		confirmCallback.confirm(correlationData, false, "err");
		then(messageInfoJpaRepository).should().updateRetryCount(10L);

		RabbitTemplate.ReturnsCallback returnsCallback = getReturnsCallback(template);
		assertNotNull(returnsCallback);

		MessageProperties properties = new MessageProperties();
		properties.setCorrelationId("11");
		Message message = new Message("body".getBytes(StandardCharsets.UTF_8), properties);
		ReturnedMessage returned = new ReturnedMessage(message, 312, "text", "ex", "rk");

		returnsCallback.returnedMessage(returned);
		then(messageInfoJpaRepository).should().updateRetryCount(11L);
	}

	private RabbitTemplate.ConfirmCallback getConfirmCallback(RabbitTemplate template) {
		Object callback = null;
		try {
			callback = ReflectionTestUtils.invokeMethod(template, "getConfirmCallback");
		} catch (RuntimeException ignored) {
			// fallback to field access
		}
		if (callback == null) {
			callback = ReflectionTestUtils.getField(template, "confirmCallback");
		}
		return (RabbitTemplate.ConfirmCallback) callback;
	}

	private RabbitTemplate.ReturnsCallback getReturnsCallback(RabbitTemplate template) {
		Object callback = null;
		try {
			callback = ReflectionTestUtils.invokeMethod(template, "getReturnsCallback");
		} catch (RuntimeException ignored) {
			// fallback to field access
		}
		if (callback == null) {
			callback = ReflectionTestUtils.getField(template, "returnsCallback");
		}
		return (RabbitTemplate.ReturnsCallback) callback;
	}

	private RabbitMqConfig newConfig() {
		RabbitMqConfig config = new RabbitMqConfig(messageInfoJpaRepository);
		ReflectionTestUtils.setField(config, "folderExchangeName", "folder.exchange");
		ReflectionTestUtils.setField(config, "messageTtl", 1234);
		ReflectionTestUtils.setField(config, "folderMoveQueueName", "folder.move.queue");
		ReflectionTestUtils.setField(config, "folderMoveBindingKey", "folder.move.key");
		ReflectionTestUtils.setField(config, "folderMoveDlxExchangeName", "folder.move.dlx.exchange");
		ReflectionTestUtils.setField(config, "folderMoveDlqName", "folder.move.dlx.queue");
		ReflectionTestUtils.setField(config, "folderMoveDlxRoutingKey", "folder.move.dlx.key");
		return config;
	}
}
