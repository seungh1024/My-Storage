package com.woowacamp.storage.domain.file.entity;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.woowacamp.storage.domain.file.dto.request.FileUploadRequestDto;
import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.global.constant.PermissionType;
import com.woowacamp.storage.global.constant.UploadStatus;

import static org.junit.jupiter.api.Assertions.*;

class FileMetadataFactoryTest {

	@Test
	@DisplayName("확장자 미포함 파일명은 확장자 추가 후 메타데이터 생성")
	void buildInitialMetadata_appendsExtension() {
		FolderMetadata parent = folder(10L, "/root/");
		FileUploadRequestDto dto = new FileUploadRequestDto(1L, 10L, 123L, 2L, 99L, "doc", "txt");

		FileMetadata metadata = FileMetadataFactory.buildInitialMetadata(parent, dto, "uuid-1");

		assertEquals("doc.txt", metadata.getUploadFileName());
		assertEquals("/root/doc.txt/", metadata.getNameFullPath());
		assertEquals(metadata.getNameFullPath().length(), metadata.getNamePathLength());
		assertEquals(parent.getId(), metadata.getRootId());
		assertEquals(dto.userId(), metadata.getOwnerId());
		assertEquals(dto.creatorId(), metadata.getCreatorId());
		assertEquals(parent.getId(), metadata.getParentFolderId());
		assertEquals(dto.fileSize(), metadata.getFileSize());
		assertEquals("uuid-1", metadata.getUuidFileName());
		assertEquals(UploadStatus.PENDING, metadata.getUploadStatus());
		assertEquals(parent.getPermissionType(), metadata.getPermissionType());
		assertEquals(parent.getSharingExpiredAt(), metadata.getSharingExpiredAt());
		assertEquals("FILE", metadata.getFileType());
	}

	@Test
	@DisplayName("확장자가 이미 포함된 파일명은 그대로 사용한다")
	void buildInitialMetadata_keepsExistingExtension() {
		FolderMetadata parent = folder(10L, "/root/");
		FileUploadRequestDto dto = new FileUploadRequestDto(1L, 10L, 123L, 2L, 99L, "image.JPG", "jpg");

		FileMetadata metadata = FileMetadataFactory.buildInitialMetadata(parent, dto, "uuid-2");

		assertEquals("image.JPG", metadata.getUploadFileName());
		assertEquals("/root/image.JPG/", metadata.getNameFullPath());
	}

	private FolderMetadata folder(Long id, String nameFullPath) {
		LocalDateTime now = LocalDateTime.of(2024, 1, 1, 0, 0);
		return FolderMetadata.builder()
			.id(id)
			.rootId(id)
			.ownerId(1L)
			.creatorId(1L)
			.createdAt(now)
			.updatedAt(now)
			.parentFolderId(null)
			.uploadFolderName("root")
			.size(0)
			.sharingExpiredAt(now.minusYears(1))
			.permissionType(PermissionType.WRITE)
			.isDeleted(false)
			.nameFullPath(nameFullPath)
			.idFullPath("/")
			.namePathLength(nameFullPath.length())
			.build();
	}
}
