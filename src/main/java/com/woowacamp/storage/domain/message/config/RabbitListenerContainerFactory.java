package com.woowacamp.storage.domain.message.config;

import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitListenerContainerFactory {

	@Value("${spring.rabbitmq.folder-size-consumerSize}")
	private int consumerSize;
	@Value("${spring.rabbitmq.folder-size-maxConsumerSize}")
	private int maxConsumerSize;
	@Value("${spring.rabbitmq.folder-size-batchSize}")
	private int batchSize;
	@Value("${spring.rabbitmq.folder-size-receiveTimeout}")
	private long receiveTimeout;


	@Bean
	public SimpleRabbitListenerContainerFactory folderSizeFactory(ConnectionFactory connectionFactory, MessageConverter messageConverter) {
		SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
		factory.setConnectionFactory(connectionFactory);
		factory.setMessageConverter(messageConverter);
		factory.setConcurrentConsumers(consumerSize);   // 동시에 처리할 소비자 수
		factory.setMaxConcurrentConsumers(maxConsumerSize);

		// batch listener 활성화
		factory.setBatchListener(true);
		factory.setConsumerBatchEnabled(true);

		// batch 크기, 타임아웃 설정
		factory.setBatchSize(batchSize); // n개씩 모아서 처리
		factory.setReceiveTimeout(receiveTimeout); // 안모이면 100ms 이후 수신

		return factory;
	}
}
