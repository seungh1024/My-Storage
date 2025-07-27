package com.woowacamp.storage.global.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {

	@Value("${spring.rabbitmq.addresses}")
	private String addresses;
	@Value("${spring.rabbitmq.username}")
	private String userName;
	@Value("${spring.rabbitmq.password}")
	private String password;
	@Value("${spring.rabbitmq.virtual-host}")
	private String virtualHost;
	@Value("${spring.rabbitmq.folder-size-queue}")
	private String folderSizeQueue;
	@Value("${spring.rabbitmq.folder-size-exchange}")
	private String folderSizeExchange;
	@Value("${spring.rabbitmq.folder-size-key}")
	private String folderSizeKey;

	@Value("${spring.rabbitmq.folder-size-dlx-queue}")
	private String folderSizeDlxQueue;
	@Value("${spring.rabbitmq.folder-size-dlx-exchange}")
	private String folderSizeDlxExchange;
	@Value("${spring.rabbitmq.folder-size-dlx-key}")
	private String folderSizeDlxKey;

	@Bean
	public ConnectionFactory connectionFactory() {
		CachingConnectionFactory connectionFactory = new CachingConnectionFactory();
		connectionFactory.setAddresses(addresses);
		connectionFactory.setUsername(userName);
		connectionFactory.setPassword(password);
		connectionFactory.setVirtualHost(virtualHost);
		connectionFactory.setConnectionTimeout(3000);

		return connectionFactory;
	}

	@Bean
	public Queue folderSizeQueue() {
		return QueueBuilder.durable(folderSizeQueue)
			.withArgument("x-message-ttl", 60000)  // 메시지 TTL 60000ms = 1분
			.withArgument("x-dead-letter-exchange", folderSizeDlxExchange)  // DLX 설정
			.withArgument("x-dead-letter-routing-key", folderSizeDlxKey)  // DLX 라우팅 키
			.build();
	}

	/**
	 * 라우팅 키에 따라 메세지를 특정 큐로 라우팅하기 위해 TopicExchange 사용.
	 * 라우팅 키와 정확히 일치하는 큐로 메세지 전달하려면 DirecExchange, Broadcast 하려면 FanoutExchange 사용.
	 * @return
	 */
	@Bean
	public TopicExchange folderSizeExchange() {
		return new TopicExchange(folderSizeExchange);
	}

	@Bean
	public Binding folderSizeBinding(TopicExchange folderSizeExchange, Queue folderSizeQueue) {
		return BindingBuilder.bind(folderSizeQueue).to(folderSizeExchange).with(folderSizeKey);
	}

	@Bean
	public DirectExchange dlxExchange() {
		return new DirectExchange(folderSizeDlxExchange);
	}

	@Bean
	public Queue dlq() {
		return QueueBuilder.durable(folderSizeDlxQueue).build();
	}

	@Bean
	public Binding dlqBinding(Queue dlq, DirectExchange dlxExchange) {
		return BindingBuilder.bind(dlq).to(dlxExchange).with(folderSizeDlxKey);
	}

	/**
	 * json 형태로 메세지 변경
	 * @return
	 */
	@Bean
	MessageConverter messageConverter() {
		return new Jackson2JsonMessageConverter();
	}

	/**
	 * 구성한 connection factory, message converter로 템플릿 구성
	 * @param connectionFactory
	 * @param messageConverter
	 * @return
	 */
	@Bean
	RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter messageConverter) {
		RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
		rabbitTemplate.setMessageConverter(messageConverter);
		return rabbitTemplate;
	}
}
