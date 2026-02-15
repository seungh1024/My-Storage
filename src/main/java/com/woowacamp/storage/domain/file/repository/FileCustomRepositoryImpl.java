package com.woowacamp.storage.domain.file.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.woowacamp.storage.domain.file.entity.FileMetadata;
import com.woowacamp.storage.domain.file.entity.QFileMetadata;
import com.woowacamp.storage.domain.folder.dto.type.FolderContentsSortField;
import com.woowacamp.storage.global.constant.PermissionType;
import com.woowacamp.storage.global.constant.UploadStatus;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class FileCustomRepositoryImpl implements FileCustomRepository {
	private static final QFileMetadata fileMetadata = QFileMetadata.fileMetadata;
	private final JPAQueryFactory queryFactory;

	public List<FileMetadata> selectFilesWithPagination(long parentId, long cursorId, FolderContentsSortField sortBy,
		Sort.Direction direction, int limit, LocalDateTime dateTime, Long size) {

		// 기본 쿼리 구성 , where 조건 parentFolderId, uploadStatus
		JPAQuery<FileMetadata> query = queryFactory.selectFrom(fileMetadata)
			.where(fileMetadata.parentFolderId.eq(parentId))
			.where(fileMetadata.uploadStatus.eq(UploadStatus.SUCCESS));

		// 정렬 조건 적용
		BooleanExpression cursorCondition;
		OrderSpecifier<?> orderSpecifier;
		switch (sortBy) {
			case CREATED_AT:

				if (direction.isAscending()) {
					cursorCondition = fileMetadata.createdAt.goe(dateTime)
						.and(fileMetadata.createdAt.gt(dateTime).or(fileMetadata.id.gt(cursorId)));
					orderSpecifier = fileMetadata.createdAt.asc();
				} else {
					cursorCondition = fileMetadata.createdAt.loe(dateTime)
						.and(fileMetadata.createdAt.lt(dateTime).or(fileMetadata.id.gt(cursorId)));
					orderSpecifier = fileMetadata.createdAt.desc();
				}
				break;
			case DATA_SIZE:
				if (direction.isAscending()) {
					cursorCondition = fileMetadata.fileSize.goe(size)
						.and(fileMetadata.fileSize.gt(size).or(fileMetadata.id.gt(cursorId)));
					orderSpecifier = fileMetadata.fileSize.asc();
				} else {
					cursorCondition = fileMetadata.fileSize.loe(size)
						.and(fileMetadata.fileSize.lt(size).or(fileMetadata.id.gt(cursorId)));
					orderSpecifier = fileMetadata.fileSize.desc();
				}
				break;
			default:
				// 조건에 없는 Enum 값이 들어오면 id 기준 정렬
				cursorCondition = fileMetadata.id.gt(cursorId);
				orderSpecifier = fileMetadata.id.asc();
		}

		// 커서 조건 추가
		query = query.where(cursorCondition);

		// 정렬 조건이 같으면 id 기준으로 정렬
		query = query.orderBy(orderSpecifier, fileMetadata.id.asc());

		// limit 제한 추가
		query.limit(limit);

		return query.fetch();
	}

	@Override
	public void updateShareStatusInBatch(List<Long> fileIdsToUpdate, PermissionType permissionType,
		LocalDateTime sharingExpireAt) {
		queryFactory.update(fileMetadata)
			.set(fileMetadata.permissionType, permissionType)
			.set(fileMetadata.sharingExpiredAt, sharingExpireAt)
			.where(fileMetadata.id.in(fileIdsToUpdate))
			.execute();
	}
}
