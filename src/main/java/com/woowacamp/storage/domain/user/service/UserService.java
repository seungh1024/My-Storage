package com.woowacamp.storage.domain.user.service;

import static com.woowacamp.storage.domain.folder.entity.FolderMetadataFactory.*;
import static com.woowacamp.storage.domain.user.entity.UserFactory.*;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataJpaRepository;
import com.woowacamp.storage.domain.user.dto.UserDto;
import com.woowacamp.storage.domain.user.dto.request.CreateUserReqDto;
import com.woowacamp.storage.domain.user.entity.User;
import com.woowacamp.storage.domain.user.repository.UserRepository;
import com.woowacamp.storage.global.error.ErrorCode;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class UserService {
	private static final String rootFolderName = "rootFolder";
	private final UserRepository userRepository;
	private final FolderMetadataJpaRepository folderMetadataRepository;

	@Transactional(readOnly = true)
	public UserDto findById(long userId) {
		User user = userRepository.findById(userId)
			.orElseThrow(ErrorCode.USER_NOT_FOUND::baseException);

		return new UserDto(user.getId(), user.getRootFolderId(), user.getUserName());
	}

	@Transactional
	public UserDto save(CreateUserReqDto req) {
		LocalDateTime now = LocalDateTime.now();

		FolderMetadata rootFolder = folderMetadataRepository.save(createFolderMetadataBySignup(rootFolderName));

		User user = userRepository.save(createUser(req.userName(), rootFolder));

		rootFolder.initOwnerId(user.getId());
		rootFolder.initCreatorId(user.getId());

		return new UserDto(user.getId(), user.getRootFolderId(), user.getUserName());
	}
}
