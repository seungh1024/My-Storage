package com.woowacamp.storage.domain.dummy;

import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;

import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.global.constant.PermissionType;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

class FolderDummyRepositoryTest {

	@Test
	@DisplayName("saveAll: parentFolderId null 여부에 따라 setNull/setLong 호출")
	void saveAll_setsNullableParentId() throws Exception {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		FolderDummyRepository repository = new FolderDummyRepository(jdbcTemplate);

		FolderMetadata nullParent = folder(1L, null);
		FolderMetadata withParent = folder(2L, 5L);
		List<FolderMetadata> list = List.of(nullParent, withParent);

		repository.saveAll(list);

		ArgumentCaptor<ParameterizedPreparedStatementSetter<FolderMetadata>> captor =
			ArgumentCaptor.forClass(ParameterizedPreparedStatementSetter.class);
		then(jdbcTemplate).should().batchUpdate(anyString(), eq(list), eq(list.size()), captor.capture());

		PreparedStatement ps1 = mock(PreparedStatement.class);
		captor.getValue().setValues(ps1, nullParent);
		then(ps1).should().setNull(7, Types.BIGINT);

		PreparedStatement ps2 = mock(PreparedStatement.class);
		captor.getValue().setValues(ps2, withParent);
		then(ps2).should().setLong(7, 5L);
	}

	@Test
	@DisplayName("batchUpdateNamePathLength: namePathLength와 id를 바인딩")
	void batchUpdateNamePathLength_bindsParams() throws Exception {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		FolderDummyRepository repository = new FolderDummyRepository(jdbcTemplate);

		FolderMetadata folder = folder(3L, 1L);
		folder.updateNamePathLength(42);

		repository.batchUpdateNamePathLength(List.of(folder));

		ArgumentCaptor<ParameterizedPreparedStatementSetter<FolderMetadata>> captor =
			ArgumentCaptor.forClass(ParameterizedPreparedStatementSetter.class);
		then(jdbcTemplate).should().batchUpdate(anyString(), eq(List.of(folder)), eq(1), captor.capture());

		PreparedStatement ps = mock(PreparedStatement.class);
		captor.getValue().setValues(ps, folder);
		then(ps).should().setInt(1, 42);
		then(ps).should().setLong(2, 3L);
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
