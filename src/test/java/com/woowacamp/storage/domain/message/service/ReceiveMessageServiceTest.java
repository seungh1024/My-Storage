package com.woowacamp.storage.domain.message.service;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.woowacamp.storage.domain.folder.service.FolderMoveProcessor;
import com.woowacamp.storage.domain.folder.service.FolderService;
import com.woowacamp.storage.domain.message.dto.FolderMoveMessageDto;
import com.woowacamp.storage.domain.message.dto.FolderSizeMessageDto;
import com.woowacamp.storage.domain.message.event.MessageInfoEvent;
import com.woowacamp.storage.domain.message.repository.MessageInfoJpaRepository;
import com.woowacamp.storage.domain.message.util.EventType;
import com.woowacamp.storage.global.error.CustomException;
import com.woowacamp.storage.global.error.ErrorCode;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class ReceiveMessageServiceTest {

	@InjectMocks
	private ReceiveMessageService receiveMessageService;

	@Mock
	private FolderService folderService;

	@Mock
	private FolderMoveProcessor folderMoveProcessor;

	@Mock
	private MessageInfoJpaRepository messageInfoJpaRepository;

	@Mock
	private ApplicationEventPublisher publisher;

	@Test
	@DisplayName("handleFolderSizeEvent: update 성공 시 예외 없이 처리")
	void handleFolderSizeEvent_success() {
		FolderSizeMessageDto message = new FolderSizeMessageDto(1L, 10L, 100L, EventType.FOLDER_SIZE);
		given(folderService.updateFolderSize(1L, 10L, 100L)).willReturn(1);

		assertDoesNotThrow(() -> receiveMessageService.handleFolderSizeEvent(message));

		then(folderService).should().updateFolderSize(1L, 10L, 100L);
	}

	@Test
	@DisplayName("handleFolderSizeEvent: update 실패 시 예외 발생")
	void handleFolderSizeEvent_failure() {
		FolderSizeMessageDto message = new FolderSizeMessageDto(1L, 10L, 100L, EventType.FOLDER_SIZE);
		given(folderService.updateFolderSize(1L, 10L, 100L)).willReturn(0);

		CustomException ex = assertThrows(CustomException.class,
			() -> receiveMessageService.handleFolderSizeEvent(message));
		assertEquals(ErrorCode.CANNOT_UPDATE_SIZE.getMessage(), ex.getMessage());
	}

	@Test
	@DisplayName("handleFolderMoveEvent: 이미 처리된 메시지면 종료")
	void handleFolderMoveEvent_alreadyProcessed() {
		FolderMoveMessageDto message = new FolderMoveMessageDto(2L, 20L, EventType.FOLDER_MOVE);
		given(messageInfoJpaRepository.existsByIdAndStatusIn(eq(2L), anyList())).willReturn(false);

		receiveMessageService.handleFolderMoveEvent(message);

		then(publisher).shouldHaveNoInteractions();
		then(folderMoveProcessor).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("handleFolderMoveEvent: 정상 처리 시 이벤트 발행 및 프로세스 수행")
	void handleFolderMoveEvent_success() {
		FolderMoveMessageDto message = new FolderMoveMessageDto(3L, 30L, EventType.FOLDER_MOVE);
		given(messageInfoJpaRepository.existsByIdAndStatusIn(eq(3L), anyList())).willReturn(true);

		receiveMessageService.handleFolderMoveEvent(message);

		ArgumentCaptor<MessageInfoEvent> captor = ArgumentCaptor.forClass(MessageInfoEvent.class);
		then(publisher).should().publishEvent(captor.capture());
		assertEquals(3L, captor.getValue().getId());

		then(folderMoveProcessor).should().processMove(30L);
	}
}
