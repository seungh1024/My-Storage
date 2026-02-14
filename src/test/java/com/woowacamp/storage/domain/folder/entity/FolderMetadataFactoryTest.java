package com.woowacamp.storage.domain.folder.entity;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.woowacamp.storage.domain.folder.dto.request.CreateFolderReqDto;
import com.woowacamp.storage.domain.user.entity.User;
import com.woowacamp.storage.global.constant.CommonConstant;
import com.woowacamp.storage.global.constant.PermissionType;

import static org.junit.jupiter.api.Assertions.*;

class FolderMetadataFactoryTest {

	@Test
	@DisplayName("회원가입용 루트 폴더 메타데이터 생성")
	void createFolderMetadataBySignup_buildsRootFolder() {
		FolderMetadata metadata = FolderMetadataFactory.createFolderMetadataBySignup("root");

		assertEquals("root", metadata.getUploadFolderName());
		assertEquals(PermissionType.NONE, metadata.getPermissionType());
		assertEquals(CommonConstant.UNAVAILABLE_TIME, metadata.getSharingExpiredAt());
		assertEquals("/", metadata.getNameFullPath());
		assertEquals(1, metadata.getNamePathLength());
		assertEquals("/", metadata.getIdFullPath());
	}

	@Test
	@DisplayName("일반 폴더 생성 시 부모 경로 기반으로 메타데이터 생성")
	void createFolderMetadata_buildsFromParent() {
		User user = User.builder().id(1L).rootFolderId(100L).userName("u").build();
		FolderMetadata parent = parentFolder();
		CreateFolderReqDto req = new CreateFolderReqDto(1L, 100L, 10L, "child", 2L);

		FolderMetadata metadata = FolderMetadataFactory.createFolderMetadata(user, parent, req);

		assertEquals(user.getRootFolderId(), metadata.getRootId());
		assertEquals(user.getId(), metadata.getOwnerId());
		assertEquals(req.creatorId(), metadata.getCreatorId());
		assertEquals(req.parentFolderId(), metadata.getParentFolderId());
		assertEquals(req.uploadFolderName(), metadata.getUploadFolderName());
		assertEquals(parent.getSharingExpiredAt(), metadata.getSharingExpiredAt());
		assertEquals(PermissionType.WRITE, metadata.getPermissionType());
		assertEquals(parent.getIdFullPath(), metadata.getIdFullPath());
		assertEquals("/parent/child/", metadata.getNameFullPath());
		assertEquals(metadata.getNameFullPath().length(), metadata.getNamePathLength());
	}

	private FolderMetadata parentFolder() {
		LocalDateTime now = LocalDateTime.of(2024, 1, 1, 0, 0);
		return FolderMetadata.builder()
			.id(10L)
			.rootId(100L)
			.ownerId(1L)
			.creatorId(1L)
			.createdAt(now)
			.updatedAt(now)
			.parentFolderId(null)
			.uploadFolderName("parent")
			.size(0)
			.sharingExpiredAt(now.minusYears(1))
			.permissionType(PermissionType.WRITE)
			.isDeleted(false)
			.nameFullPath("/parent/")
			.idFullPath("/10/")
			.namePathLength(8)
			.build();
	}
}
