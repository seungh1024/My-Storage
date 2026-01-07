package com.woowacamp.storage.domain.dummy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.user.entity.User;
import com.woowacamp.storage.global.constant.PermissionType;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class DummyService {
	private final UserDummyRepository userDummyRepository;
	private final FolderDummyRepository folderDummyRepository;
	private final DummyThreadPoolExecutor threadPoolExecutor = new DummyThreadPoolExecutor(8, 32);

	public void createUserDummy(DummyRequestUserDto userDto) {
		threadPoolExecutor.init();

		long startId = userDto.startId();
		long endId = userDto.endId();

		for (long i = startId; i <= endId; i+=500) {
			List<User> users = new ArrayList<>();
			List<FolderMetadata> folders = new ArrayList<>();

			long range = Math.min(endId+1, i + 500);
			for (long id = i; id < range; id++) {
				User user = User.builder().id(id).rootFolderId(id).userName("user " + id).build();
				users.add(user);

				FolderMetadata folderMetadata = FolderMetadata.builder()
					.id(id)
					.rootId(id)
					.ownerId(id)
					.creatorId(id)
					.createdAt(LocalDateTime.now())
					.updatedAt(LocalDateTime.now())
					.parentFolderId(null)
					.uploadFolderName("folder " + id)
					.size(0)
					.sharingExpiredAt(LocalDateTime.now().minusYears(1))
					.permissionType(PermissionType.NONE)
					.isDeleted(false)
					.nameFullPath("/")
					.idFullPath("/")
					.build();
				folders.add(folderMetadata);
			}

			threadPoolExecutor.execute(() ->{
				userDummyRepository.saveAll(users);
				folderDummyRepository.saveAll(folders);
			});
		}

		threadPoolExecutor.waitToEnd();
	}
}
