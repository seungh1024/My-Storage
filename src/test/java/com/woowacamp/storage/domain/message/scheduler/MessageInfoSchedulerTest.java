package com.woowacamp.storage.domain.message.scheduler;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.woowacamp.storage.container.ContainerBaseConfig;
import com.woowacamp.storage.domain.folder.dto.message.FolderSizeMessageDto;
import com.woowacamp.storage.domain.message.entity.MessageInfo;
import com.woowacamp.storage.domain.message.repository.MessageInfoJpaRepository;
import com.woowacamp.storage.domain.message.repository.MessageInfoRepository;
import com.woowacamp.storage.domain.message.service.SendMessageService;
import com.woowacamp.storage.domain.message.util.EventType;
import com.woowacamp.storage.domain.message.util.JsonSerializer;
import com.woowacamp.storage.domain.message.util.MessageStatus;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class MessageInfoSchedulerTest extends ContainerBaseConfig {

	@Autowired
	private MessageInfoRepository messageInfoRepository;
	@Autowired
	private MessageInfoJpaRepository messageInfoJpaRepository;
	@Autowired
	private SendMessageService sendMessageService;
	@Autowired
	private JsonSerializer jsonSerializer;
	@Autowired
	private MessageInfoScheduler messageInfoScheduler;

	@Value("${constant.batchSize}")
	int batchSize;

	@Value("${constant.deleteSize}")
	int deleteSize;

	@Value("${constant.maxRetry}")
	int maxRetry;

	@Nested
	@DisplayName("메세지 재발행 테스트")
	class FolderMoveTest {

		@Test
		@DisplayName("PENDING 상태의 메세지를 처리하면 SUCCESS 상태로 변한다.")
		void retry_pending_message_will_be_success() {
			long temp = 1;
			for (int i = 0; i < batchSize; i++) {
				FolderSizeMessageDto dto = new FolderSizeMessageDto(temp,temp,temp,EventType.FOLDER_SIZE);
				temp++;
				MessageInfo messageInfo = new MessageInfo("FolderMove", EventType.FOLDER_SIZE, jsonSerializer.serialize(dto));
				messageInfoJpaRepository.save(messageInfo);
			}

			messageInfoScheduler.retryMessage();

			List<MessageInfo> messageInfoList = messageInfoJpaRepository.findAll();

			long count = messageInfoList.stream()
				.filter(messageInfo -> messageInfo.getStatus().equals(MessageStatus.SUCCESS))
				.count();

			assertEquals(batchSize, count);
		}

		@Test
		@DisplayName("SUCCESS 상태의 메세지는 스케줄러가 삭제한다.")
		void success_message_will_be_deleted() {
			long temp = 1;
			for (int i = 0; i < batchSize; i++) {
				FolderSizeMessageDto dto = new FolderSizeMessageDto(temp,temp,temp,EventType.FOLDER_SIZE);
				temp++;
				MessageInfo messageInfo = new MessageInfo("FolderMove", EventType.FOLDER_SIZE, jsonSerializer.serialize(dto));
				messageInfo.markSent();
				messageInfoJpaRepository.save(messageInfo);
			}

			messageInfoScheduler.deleteSuccessMessage();

			List<MessageInfo> messageInfoList = messageInfoJpaRepository.findAll();

			assertTrue(messageInfoList.isEmpty());
		}

		@Test
		@DisplayName("미처리가 최대치를 초과하면 FAILED 상태로 변한다.")
		void message_will_be_failed_when_retryCnt_over_maxRetry() {
			long temp = 1;
			for (int i = 0; i < batchSize; i++) {
				FolderSizeMessageDto dto = new FolderSizeMessageDto(temp,temp,temp,EventType.FOLDER_SIZE);
				temp++;
				MessageInfo messageInfo = new MessageInfo("FolderMove", EventType.FOLDER_SIZE, jsonSerializer.serialize(dto));
				for (int j = 0; j <= maxRetry; j++) {
					messageInfo.incrementRetryCount();
				}
				messageInfoJpaRepository.save(messageInfo);
			}

			messageInfoScheduler.failMessageHandler();

			List<MessageInfo> messageInfoList = messageInfoJpaRepository.findAll()
				.stream()
				.filter(message -> message.getStatus().equals(MessageStatus.FAILED))
				.toList();

			assertEquals(batchSize, messageInfoList.size());
		}
	}

}