package com.woowacamp.storage.container;

import java.time.Duration;
import java.util.stream.Stream;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * 통합 테스트용 TestContainers 공통 설정
 *
 * static initializer를 사용하지 않고, Spring 컨텍스트 초기화 시점에
 * 컨테이너를 지연 시작(lazy start)한다.
 */
public abstract class ContainerBaseConfig {
	private static final MySQLContainer<?> MY_SQL_CONTAINER = new MySQLContainer<>(DockerImageName.parse("mysql:8.0.33"))
		.withDatabaseName("test")
		.withUsername("test")
		.withPassword("test")
		.withReuse(true)
		.waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));

	private static final GenericContainer<?> REDIS_CONTAINER = new GenericContainer<>(DockerImageName.parse("redis:7"))
		.withExposedPorts(6379)
		.withReuse(true)
		.waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));

	private static final RabbitMQContainer RABBIT_MQ_CONTAINER =
		new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.12-management"))
			.withReuse(true)
			.withStartupTimeout(Duration.ofMinutes(3));

	private static volatile boolean containersStarted = false;

	private static synchronized void ensureContainersStarted() {
		if (containersStarted) {
			return;
		}
		Startables.deepStart(Stream.of(MY_SQL_CONTAINER, REDIS_CONTAINER, RABBIT_MQ_CONTAINER)).join();
		containersStarted = true;
	}

	@DynamicPropertySource
	static void setProperties(DynamicPropertyRegistry registry) {
		ensureContainersStarted();

		// MySQL 설정
		registry.add("spring.datasource.url", MY_SQL_CONTAINER::getJdbcUrl);
		registry.add("spring.datasource.username", MY_SQL_CONTAINER::getUsername);
		registry.add("spring.datasource.password", MY_SQL_CONTAINER::getPassword);
		registry.add("spring.datasource.driver-class-name", MY_SQL_CONTAINER::getDriverClassName);
		registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.MySQL8Dialect");

		// Redis 설정 (기본 Redis 클라이언트용)
		registry.add("spring.redis.host", REDIS_CONTAINER::getHost);
		registry.add("spring.redis.port", REDIS_CONTAINER::getFirstMappedPort);

		// Redisson 설정 (분산 락용)
		registry.add("spring.redisson.address", 
			() -> "redis://" + REDIS_CONTAINER.getHost() + ":" + REDIS_CONTAINER.getFirstMappedPort());

		// RabbitMQ 설정
		registry.add("spring.rabbitmq.addresses",
			() -> RABBIT_MQ_CONTAINER.getHost() + ":" + RABBIT_MQ_CONTAINER.getAmqpPort());
		registry.add("spring.rabbitmq.username", () -> "guest");
		registry.add("spring.rabbitmq.password", () -> "guest");
	}

	/**
	 * RabbitMQ 큐를 비우는 헬퍼 메서드
	 * 테스트 간 메시지 격리를 위해 @BeforeEach에서 호출
	 */
	protected void purgeAllQueues(RabbitTemplate rabbitTemplate) {
		try {
			rabbitTemplate.execute(channel -> {
				// folder.size.queue 비우기
				try {
					channel.queuePurge("folder.size.queue");
				} catch (Exception e) {
					// 큐가 없으면 무시
				}

				// folder.move.queue 비우기
				try {
					channel.queuePurge("folder.move.queue");
				} catch (Exception e) {
					// 큐가 없으면 무시
				}

				// DLQ도 비우기
				try {
					channel.queuePurge("folder.dlx.size.queue");
				} catch (Exception e) {
					// 큐가 없으면 무시
				}

				try {
					channel.queuePurge("folder.dlx.move.queue");
				} catch (Exception e) {
					// 큐가 없으면 무시
				}

				return null;
			});
		} catch (Exception e) {
			// 전체 작업 실패 시 무시
		}
	}

}
