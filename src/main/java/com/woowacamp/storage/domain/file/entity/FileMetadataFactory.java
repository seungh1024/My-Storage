package com.woowacamp.storage.domain.file.entity;

import java.time.LocalDateTime;

import com.woowacamp.storage.domain.file.dto.request.FileUploadRequestDto;
import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.user.entity.User;
import com.woowacamp.storage.global.aop.type.FileType;
import com.woowacamp.storage.global.constant.UploadStatus;

public class FileMetadataFactory {

	public static FileMetadata buildInitialMetadata(User user, long parentFolderId, long fileSize, String uuidFileName,
		String fileName, String fileType, String thumbnailUUID, long creatorId, FolderMetadata parentFolderMetadata) {
		LocalDateTime now = LocalDateTime.now();
		return FileMetadata.builder()
			.rootId(user.getRootFolderId())
			.creatorId(creatorId)
			.ownerId(user.getId())
			.parentFolderId(parentFolderId)
			.fileSize(fileSize)
			.uuidFileName(uuidFileName)
			.uploadStatus(UploadStatus.PENDING)
			.uploadFileName(fileName)
			.fileType(fileType)
			.sharingExpiredAt(parentFolderMetadata.getSharingExpiredAt())
			.createdAt(now)
			.updatedAt(now)
			.thumbnailUUID(thumbnailUUID)
			.permissionType(parentFolderMetadata.getPermissionType())
			.build();
	}

	public static FileMetadata buildInitialMetadata(FolderMetadata parentFolder, FileUploadRequestDto dto, String uuidFileName) {
		LocalDateTime now = LocalDateTime.now();
		return FileMetadata.builder()
			.rootId(parentFolder.getId())
			.creatorId(dto.creatorId())
			.ownerId(dto.userId())
			.fileType(FileType.FILE.name())
			.createdAt(now)
			.updatedAt(now)
			.parentFolderId(parentFolder.getId())
			.fileSize(dto.fileSize())
			.uploadFileName(dto.fileName())
			.uuidFileName(uuidFileName)
			.uploadStatus(UploadStatus.PENDING)
			.sharingExpiredAt(parentFolder.getSharingExpiredAt())
			.permissionType(parentFolder.getPermissionType())
			.build();
	}
}
