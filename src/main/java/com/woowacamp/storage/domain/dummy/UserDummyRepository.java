package com.woowacamp.storage.domain.dummy;

import java.sql.PreparedStatement;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.woowacamp.storage.domain.user.entity.User;
import com.woowacamp.storage.global.util.StorageStringUtil;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Repository
@RequiredArgsConstructor
@Slf4j
public class UserDummyRepository {
	private final JdbcTemplate jdbcTemplate;

	public void saveAll(List<User> users) {
		log.info("[Save User Dummy] List : {}",users);
		String sql = StorageStringUtil.format("""
            INSERT INTO users (user_id,user_name,root_folder_id)
            VALUES(?,?,?)
            """);



		jdbcTemplate.batchUpdate(sql,users,users.size(),(PreparedStatement ps, User user)->{
			ps.setLong(1,user.getId());
			ps.setString(2,user.getUserName());
			ps.setLong(3,user.getRootFolderId());
		});

	}
}
