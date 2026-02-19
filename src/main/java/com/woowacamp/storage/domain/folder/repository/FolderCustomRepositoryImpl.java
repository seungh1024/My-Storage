package com.woowacamp.storage.domain.folder.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Sort;

import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.woowacamp.storage.domain.folder.dto.type.FolderContentsSortField;
import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.entity.QFolderMetadata;
import com.woowacamp.storage.global.constant.PermissionType;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class FolderCustomRepositoryImpl implements FolderCustomRepository {

	private static final QFolderMetadata folderMetadata = QFolderMetadata.folderMetadata;
	private final JPAQueryFactory queryFactory;

	public List<FolderMetadata> selectFoldersWithPagination(long parentId, long cursorId,
		FolderContentsSortField sortBy, Sort.Direction direction, int limit, LocalDateTime dateTime, Long size) {

		// 기본 쿼리 구성
		JPAQuery<FolderMetadata> query = queryFactory.selectFrom(folderMetadata)
			.where(folderMetadata.parentFolderId.eq(parentId));

		// 커서 조건 및 정렬 조건 설정
		BooleanExpression cursorCondition;
		OrderSpecifier<?> orderSpecifier;
		switch (sortBy) {
			case CREATED_AT:

				if (direction.isAscending()) {
					cursorCondition = folderMetadata.createdAt.goe(dateTime)
						.and(folderMetadata.createdAt.gt(dateTime).or(folderMetadata.id.gt(cursorId)));
					orderSpecifier = folderMetadata.createdAt.asc();
				} else {
					cursorCondition = folderMetadata.createdAt.loe(dateTime)
						.and(folderMetadata.createdAt.lt(dateTime).or(folderMetadata.id.gt(cursorId)));
					orderSpecifier = folderMetadata.createdAt.desc();
				}
				break;
			case DATA_SIZE:
				if (direction.isAscending()) {
					cursorCondition = folderMetadata.size.goe(size)
						.and(folderMetadata.size.gt(size).or(folderMetadata.id.gt(cursorId)));
					orderSpecifier = folderMetadata.size.asc();
				} else {
					cursorCondition = folderMetadata.size.loe(size)
						.and(folderMetadata.size.lt(size).or(folderMetadata.id.gt(cursorId)));
					orderSpecifier = folderMetadata.size.desc();
				}
				break;
			default:
				// 조건에 없는 Enum 값이 들어오면 id 기준 정렬
				cursorCondition = folderMetadata.id.gt(cursorId);
				orderSpecifier = folderMetadata.id.asc();
		}

		// 커서 조건 추가
		query = query.where(cursorCondition);

		// 정렬 조건이 같으면 id 기준으로 정렬
		query = query.orderBy(orderSpecifier, folderMetadata.id.asc());

		// limit 제한 추가
		query.limit(limit);

		return query.fetch();
	}

	@Override
	public void updateShareStatusInBatch(List<Long> folderIdsToUpdate, PermissionType permissionType,
		LocalDateTime sharingExpiredAt) {
		queryFactory.update(folderMetadata)
			.set(folderMetadata.permissionType, permissionType)
			.set(folderMetadata.sharingExpiredAt, sharingExpiredAt)
			.where(folderMetadata.id.in(folderIdsToUpdate))
			.execute();
	}
}
