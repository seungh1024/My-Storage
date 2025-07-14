package com.woowacamp.storage.global.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;
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
	@Value("${spring.rabbitmq.file-upload-queue}")
	private String fileUploadQueue;
	@Value("${spring.rabbitmq.file-upload-exchange}")
	private String fileUploadExchange;
	@Value("${spring.rabbitmq.file-upload-key}")
	private String fileUploadKey;
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
	public Queue fileUploadQueue() {
		return new Queue(fileUploadQueue,true);
	}

	/**
	 * 라우팅 키에 따라 메세지를 특정 큐로 라우팅하기 위해 TopicExchange 사용.
	 * 라우팅 키와 정확히 일치하는 큐로 메세지 전달하려면 DirecExchange, Broadcast 하려면 FanoutExchange 사용.
	 * @return
	 */
	@Bean
	public TopicExchange fileUploadExchange() {
		return new TopicExchange(fileUploadExchange);
	}

	@Bean
	public Binding fileUploadBinding(TopicExchange fileUploadExchange, Queue fileUploadQueue) {
		return BindingBuilder.bind(fileUploadQueue).to(fileUploadExchange).with(fileUploadKey);
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
