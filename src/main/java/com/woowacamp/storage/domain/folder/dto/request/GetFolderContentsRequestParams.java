package com.woowacamp.storage.domain.folder.dto.request;

import java.time.LocalDateTime;

import org.springframework.data.domain.Sort;

import com.woowacamp.storage.domain.folder.dto.type.CursorType;
import com.woowacamp.storage.domain.folder.dto.type.FolderContentsSortField;
import com.woowacamp.storage.global.annotation.CheckField;
import com.woowacamp.storage.global.aop.type.FieldType;
import com.woowacamp.storage.global.constant.CommonConstant;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record GetFolderContentsRequestParams(@NotNull @Positive @CheckField(value = FieldType.USER_ID) Long userId,
											 @NotNull @Positive Long cursorId, @NotNull CursorType cursorType,
											 @Positive @Max(MAX_SIZE) Integer limit, FolderContentsSortField sortBy,
											 Sort.Direction sortDirection, LocalDateTime localDateTime, Long size,
											 @CheckField(FieldType.CREATOR_ID) Long creatorId) {
	private static final int MAX_SIZE = 1000;
	private static final int DEFAULT_SIZE = 100;

	// 기본 생성자 정의
	public GetFolderContentsRequestParams {
		if (limit == null) {
			limit = DEFAULT_SIZE;
		}
		if (sortBy == null) {
			sortBy = FolderContentsSortField.CREATED_AT;
		}
		if (sortDirection == null) {
			sortDirection = Sort.Direction.DESC;
		}

		if (cursorId == null) {
			cursorId = sortDirection.isAscending() ? Long.MAX_VALUE : 1L;
		}

		if (cursorType == null) {
			cursorType = CursorType.FOLDER;
		}

		if (isFirstPage(localDateTime, size)) {
			switch (sortBy) {
				case CREATED_AT:
					if (sortDirection.isAscending()) {
						localDateTime = CommonConstant.UNAVAILABLE_TIME;
					} else {
						localDateTime = LocalDateTime.now().plusYears(1000);
					}
					break;
				case DATA_SIZE:
					if (sortDirection.isAscending()) {
						size = Long.MAX_VALUE;
					} else {
						size = 0L;
					}
					break;
				default:
					throw new IllegalArgumentException("Unsupported sortBy option: " + sortBy);
			}
		}
	}

	private boolean isFirstPage(LocalDateTime localDateTime, Long size) {
		return localDateTime == null || size == null;
	}
}
