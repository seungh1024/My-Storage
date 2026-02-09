package com.woowacamp.storage.domain.dummy;

import java.sql.PreparedStatement;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;

import com.woowacamp.storage.domain.user.entity.User;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

class UserDummyRepositoryTest {

	@Test
	@DisplayName("saveAll: user 정보를 batchUpdate로 바인딩")
	void saveAll_bindsUserParams() throws Exception {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		UserDummyRepository repository = new UserDummyRepository(jdbcTemplate);

		User user = User.builder().id(1L).rootFolderId(10L).userName("user").build();
		List<User> users = List.of(user);

		repository.saveAll(users);

		ArgumentCaptor<ParameterizedPreparedStatementSetter<User>> captor =
			ArgumentCaptor.forClass(ParameterizedPreparedStatementSetter.class);
		then(jdbcTemplate).should().batchUpdate(anyString(), eq(users), eq(users.size()), captor.capture());

		PreparedStatement ps = mock(PreparedStatement.class);
		captor.getValue().setValues(ps, user);

		then(ps).should().setLong(1, 1L);
		then(ps).should().setString(2, "user");
		then(ps).should().setLong(3, 10L);
	}
}
