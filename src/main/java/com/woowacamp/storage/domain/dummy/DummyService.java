package com.woowacamp.storage.domain.dummy;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import org.springframework.stereotype.Service;

import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataJpaRepository;
import com.woowacamp.storage.domain.user.entity.User;
import com.woowacamp.storage.global.constant.PermissionType;
import com.woowacamp.storage.global.util.StorageStringUtil;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class DummyService {
	private static final SecureRandom RANDOM = new SecureRandom();

	private final UserDummyRepository userDummyRepository;
	private final FolderDummyRepository folderDummyRepository;
	private final FolderMetadataJpaRepository folderMetadataJpaRepository;
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

	public void createFolderDummy(long startId) {
		threadPoolExecutor.init();

		Queue<FolderMetadata> idQueue = new ArrayDeque<>();
		long size = 1000;
		long minSize = 100;
		long id = 0;
		log.info("[DummyService] start createFolderDummy id={}", id);
		List<FolderMetadata> idListById = folderMetadataJpaRepository.findFolderListById(id, size);
		idQueue.addAll(idListById);
		id = idListById.get(idListById.size() - 1).getId();

		long childId = startId+1;
		long total = 10_000_000 -startId;

		List<FolderMetadata> childList = new ArrayList<>();
		while (total > 0 && !idQueue.isEmpty()) {
			FolderMetadata parent = idQueue.poll();
			long cnt = RANDOM.nextLong(1, 5);

			for (int i = 0; i < cnt; i++) {
				String folderName = "folder "+childId;
				FolderMetadata child = FolderMetadata.builder()
					.id(childId)
					.rootId(parent.getRootId())
					.ownerId(parent.getOwnerId())
					.creatorId(parent.getCreatorId())
					.createdAt(LocalDateTime.now())
					.updatedAt(LocalDateTime.now())
					.parentFolderId(parent.getId())
					.uploadFolderName(folderName)
					.size(0)
					.sharingExpiredAt(LocalDateTime.now().minusYears(1))
					.permissionType(PermissionType.NONE)
					.isDeleted(false)
					.nameFullPath(StorageStringUtil.format("{}{}/", parent.getNameFullPath(), folderName))
					.idFullPath(StorageStringUtil.format("{}{}/", parent.getIdFullPath(), childId))
					.build();
				childList.add(child);
				childId++;
			}
			total -= cnt;

			if (childList.size() >= size) {
				List<FolderMetadata> insert = new ArrayList<>(childList);
				threadPoolExecutor.execute(()->{
					folderDummyRepository.saveAll(insert);
				});
				childList.clear();
			}
			while (idQueue.size()<=minSize) {
				idListById = folderMetadataJpaRepository.findFolderListById(id,size);
				if (idListById.size() != 0) {
					idQueue.addAll(idListById);
					id = idListById.get(idListById.size() - 1).getId();
					break;
				}
			}
		}

		threadPoolExecutor.waitToEnd();
	}

	public void updateFolderPathLength(DummyRequestFolderDto userDto) {
		threadPoolExecutor.init();

		long id = userDto.startId();
		long size = 1000;
		List<FolderMetadata> folderList = new ArrayList<>();
		do {
			folderList = folderMetadataJpaRepository.findFolderListById(id, size);
			folderList.stream()
				.forEach(folder -> folder.updateNamePathLength(
					folder.getNameFullPath() == null ? 0 : folder.getNameFullPath().length()
				));

			List<FolderMetadata> updateList = new ArrayList<>(folderList);
			threadPoolExecutor.execute(() -> folderDummyRepository.batchUpdateNamePathLength(updateList));
			id = folderList.get(folderList.size()-1).getId();

		} while (folderList.size() == size);

		threadPoolExecutor.waitToEnd();
	}
}
