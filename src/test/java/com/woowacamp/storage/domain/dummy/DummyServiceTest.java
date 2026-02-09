package com.woowacamp.storage.domain.dummy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataJpaRepository;
import com.woowacamp.storage.domain.user.entity.User;
import com.woowacamp.storage.global.constant.PermissionType;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class DummyServiceTest {

	@InjectMocks
	private DummyService dummyService;

	@Mock
	private UserDummyRepository userDummyRepository;

	@Mock
	private FolderDummyRepository folderDummyRepository;

	@Mock
	private FolderMetadataJpaRepository folderMetadataJpaRepository;

	@Test
	@DisplayName("createUserDummy: 범위 내 유저/폴더 더미 저장")
	void createUserDummy_savesUsersAndFolders() {
		DummyRequestUserDto request = new DummyRequestUserDto(1L, 1L);

		dummyService.createUserDummy(request);

		ArgumentCaptor<List<User>> usersCaptor = ArgumentCaptor.forClass(List.class);
		ArgumentCaptor<List<FolderMetadata>> foldersCaptor = ArgumentCaptor.forClass(List.class);

		then(userDummyRepository).should().saveAll(usersCaptor.capture());
		then(folderDummyRepository).should().saveAll(foldersCaptor.capture());

		List<User> users = usersCaptor.getValue();
		assertEquals(1, users.size());
		assertEquals(1L, users.get(0).getId());
		assertEquals(1L, users.get(0).getRootFolderId());

		List<FolderMetadata> folders = foldersCaptor.getValue();
		assertEquals(1, folders.size());
		assertEquals(1L, folders.get(0).getId());
		assertEquals(1L, folders.get(0).getOwnerId());
		assertEquals("/", folders.get(0).getNameFullPath());
	}

	@Test
	@DisplayName("createFolderDummy: 초기 조회 후 종료(총량 0) 케이스")
	void createFolderDummy_initialLookupOnly() {
		List<FolderMetadata> initial = new ArrayList<>();
		for (long i = 0; i < 102; i++) {
			initial.add(folder(i, null));
		}
		given(folderMetadataJpaRepository.findFolderListById(0L, 1000L)).willReturn(initial);

		dummyService.createFolderDummy(10_000_000L);

		then(folderMetadataJpaRepository).should().findFolderListById(0L, 1000L);
	}

	@Test
	@DisplayName("updateFolderPathLength: nameFullPath 길이 반영 후 batchUpdate 호출")
	void updateFolderPathLength_updatesLength() {
		FolderMetadata folder = folder(1L, null);
		folder.updateNameFullPath("/root/");
		folder.updateNamePathLength(0);

		given(folderMetadataJpaRepository.findFolderListById(1L, 1000L)).willReturn(List.of(folder));

		dummyService.updateFolderPathLength(new DummyRequestFolderDto(1L));

		ArgumentCaptor<List<FolderMetadata>> captor = ArgumentCaptor.forClass(List.class);
		then(folderDummyRepository).should().batchUpdateNamePathLength(captor.capture());

		List<FolderMetadata> updated = captor.getValue();
		assertEquals(1, updated.size());
		assertEquals(updated.get(0).getNameFullPath().length(), updated.get(0).getNamePathLength());
	}

	private FolderMetadata folder(long id, Long parentId) {
		LocalDateTime now = LocalDateTime.of(2024, 1, 1, 0, 0);
		return FolderMetadata.builder()
			.id(id)
			.rootId(1L)
			.ownerId(1L)
			.creatorId(1L)
			.createdAt(now)
			.updatedAt(now)
			.parentFolderId(parentId)
			.uploadFolderName("folder-" + id)
			.size(0)
			.sharingExpiredAt(now.minusYears(1))
			.permissionType(PermissionType.NONE)
			.isDeleted(false)
			.nameFullPath("/")
			.idFullPath("/")
			.namePathLength(1)
			.build();
	}
}
