package com.woowacamp.storage.domain.folder.entity;

import java.time.LocalDateTime;

import com.woowacamp.storage.domain.folder.dto.request.CreateFolderReqDto;
import com.woowacamp.storage.domain.user.entity.User;
import com.woowacamp.storage.global.constant.CommonConstant;
import com.woowacamp.storage.global.constant.PermissionType;
import com.woowacamp.storage.global.util.StorageStringUtil;

public class FolderMetadataFactory {
	public static FolderMetadata createFolderMetadataBySignup(String folderName) {
		LocalDateTime now = LocalDateTime.now();
		return FolderMetadata.builder()
			.createdAt(now)
			.updatedAt(now)
			.uploadFolderName(folderName)
			.sharingExpiredAt(CommonConstant.UNAVAILABLE_TIME)
			.permissionType(PermissionType.NONE)
			.nameFullPath("/")
			.namePathLength(1)
			.idFullPath("/")
			.build();
	}

	public static FolderMetadata createFolderMetadata(User user, FolderMetadata parentFolder,
		CreateFolderReqDto req) {
		LocalDateTime now = LocalDateTime.now();
		String nameFullPath = StorageStringUtil.format("{}{}/", parentFolder.getNameFullPath(), req.uploadFolderName());
		int namePathLength = nameFullPath.length();

		return FolderMetadata.builder()
			.rootId(user.getRootFolderId())
			.ownerId(user.getId())
			.creatorId(req.creatorId())
			.createdAt(now)
			.updatedAt(now)
			.parentFolderId(req.parentFolderId())
			.uploadFolderName(req.uploadFolderName())
			.sharingExpiredAt(parentFolder.getSharingExpiredAt())
			.permissionType(PermissionType.WRITE)
			.nameFullPath(nameFullPath)
			.namePathLength(namePathLength)
			.idFullPath(parentFolder.getIdFullPath())
			.build();
	}

}
