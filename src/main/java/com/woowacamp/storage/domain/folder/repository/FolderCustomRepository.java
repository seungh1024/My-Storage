package com.woowacamp.storage.domain.folder.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Sort;

import com.woowacamp.storage.domain.folder.dto.type.FolderContentsSortField;
import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.global.constant.PermissionType;

public interface FolderCustomRepository {
	List<FolderMetadata> selectFoldersWithPagination(long parentId, long cursorId,
		FolderContentsSortField sortBy, Sort.Direction direction, int limit, LocalDateTime dateTime, Long size);

	void updateShareStatusInBatch(List<Long> folderIdsToUpdate, PermissionType permissionType,
		LocalDateTime sharingExpiredAt);
}
